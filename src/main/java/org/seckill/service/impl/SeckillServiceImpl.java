package org.seckill.service.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.MapUtils;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStatEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

@Service
public class SeckillServiceImpl implements SeckillService {

	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	//注入service依赖
	@Autowired
	private SeckillDao seckillDao;
	
	@Autowired
	private SuccessKilledDao successKilledDao;
	
	@Autowired
	private RedisDao redisDao;
	
	//md5混淆字符串，越复杂越好
	private final String slat = "dafgagd/l;''.'";
	
	/**
	 * 查询所有秒杀记录
	 * @return
	 */
	@Override
	public List<Seckill> getSeckillList() {
		return seckillDao.queryAll(0, 4);
	}

	/**
	 * 查询单个秒杀记录
	 * @param seckillId
	 * @return
	 */
	@Override
	public Seckill getById(long seckillId) {
		return seckillDao.queryById(seckillId);
	}

	/**
	 * 秒杀开启时输出秒杀接口地址，否则输出系统时间和秒杀时间
	 * 利用一个标记来标记秒杀是否开始
	 * @param seckillId
	 */
	@Override
	public Exposer exportSeckillUrl(long seckillId) {
		//优化点：缓存优化,一致性维护建立在超时的基础上
		//1.访问redis
		Seckill seckill = redisDao.getSeckill(seckillId);
		if(seckill == null) {
			//2.访问数据库
			seckill = seckillDao.queryById(seckillId);
			if(seckill == null) {
				return new Exposer(false, seckillId);
			}
			else {
				//3.放入redis
				redisDao.putSeckill(seckill);
			}
		}

		Date startTime = seckill.getStartTime();
		Date endTime = seckill.getEndTime();
		//系统当前时间
		Date nowTime = new Date();
		if(nowTime.getTime() < startTime.getTime() || 
			nowTime.getTime() > endTime.getTime()) {
			//时间未到或已经结束
			return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
		}
		//md5:对于任意一个字符串可以转化为一定的编码
		//转化特定字符串的过程，不可逆，对商品id进行加密
		String md5 = getMD5(seckillId);
		//正确暴露
		return new Exposer(true, md5, seckillId);
	}

	//生成md5
	private String getMD5(long seckillId) {
		String base = seckillId + "/" + slat;
		//实现md5
		String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
		return md5;
	}
	
	/**
	 * 执行秒杀操作
	 * @param seckillId
	 * @param userPhone
	 * @param md5
	 */
	@Override
	@Transactional
	/**
	 * 使用注解控制事务方法的优点：
	 * 1：开发团队达成一致约定，明确标注事务方法的编程风格
	 * 2：保证事务方法的执行时间尽可能短，不要穿插其他网络操作RPC/HTTP请求或者剥离到事务方法外部
	 * 3：不是所有的方法都需要事务，如只有一条修改操作，只读操作不需要事务控制
	 */
	public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5)
			throws SeckillException, RepeatKillException, SeckillCloseException {
		//对秒杀开启的id与要执行秒杀的id的md5值进行对比，如果不相等，则抛出系统出现异常
		if(md5 == null || !md5.equals(getMD5(seckillId))) {
			throw new SeckillException("seckill data rewrite...");
		}
		//执行秒杀逻辑：减库存 + 记录购买行为
		Date nowTime = new Date();
		try {
			//记录购买行为，插入秒杀成功表
			int insertCnt = successKilledDao.insertSuccessKilled(seckillId, userPhone);
			//唯一：seckillId, userPhone
			if(insertCnt <= 0) {
				//重复秒杀
				throw new RepeatKillException("seckill repeated...");
			}
			else {
				//减库存，热点商品竞争
				int updateCnt = seckillDao.reduceNumber(seckillId, nowTime);
				
				if(updateCnt <= 0) {
					//没有更新到记录，秒杀结束, rollback
					throw new SeckillCloseException("seckill is closed");
				}
				else {
					//秒杀成功, commit
					SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
					return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilled);
				}
			}
			
		} catch(SeckillCloseException e1) {
			throw e1;
		} catch(RepeatKillException e2) {
			throw e2;
		}
		catch (Exception e) {
			logger.error(e.getMessage(), e);
			//所有编译期异常  转化为运行期异常，如果出现异常，可以自动做roll back回滚操作
			throw new SeckillException("seckill inner error:" + e.getMessage());
		}
		
	}

	@Override
	public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) {
		System.out.println(md5);
		System.out.println(getMD5(seckillId));
		if(md5 == null || !md5.equals(getMD5(seckillId))) {
			return new SeckillExecution(seckillId, SeckillStatEnum.DATA_REWRITE);
		}
		Date killTime = new Date();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("seckillId", seckillId);
		map.put("phone", userPhone);
		map.put("killTime", killTime);
		map.put("result", null);
		//执行存储过程，result被复制
		try {
			seckillDao.killByProcedure(map);
			//获取result
			int result = MapUtils.getInteger(map, "result", -2);
			System.out.println(result);
			//这里有一点点问题，这里插入秒杀成功表的时候没有插入state属性，默认是-1了。
			if(result == 1) {
				SuccessKilled sk = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
				return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS);
			}
			else {
				System.out.println(SeckillStatEnum.stateOf(result));
				return new SeckillExecution(seckillId, SeckillStatEnum.stateOf(result));
			}
		} catch(Exception e) {
			logger.error(e.getMessage(), e);
			return new SeckillExecution(seckillId, SeckillStatEnum.INNER_ERROR);
		}
	//	return null;
		
	}

}
