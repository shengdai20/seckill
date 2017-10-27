package org.seckill.service;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
//要加入dao的xml，是因为要测试service层，需要依赖dao的xml
@ContextConfiguration({
	"classpath:spring/spring-dao.xml",
	"classpath:spring/spring-service.xml"
})
public class SeckillServiceTest {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	
	@Autowired
	private SeckillService seckillService;
	
	@Test
	public void testGetSeckillList() throws Exception {
		List<Seckill> list = seckillService.getSeckillList();
		logger.info("list={}", list);
	}
	
	@Test
	public void testGetById() throws Exception {
		long id = 1000L;
		Seckill seckill = seckillService.getById(id);
		logger.info("seckill={}", seckill);
	}
	
	//集成测试代码完整逻辑，注意可重复执行
	@Test
	public void testSeckillLogic() throws Exception {
		long id = 1001L;
		Exposer exposer = seckillService.exportSeckillUrl(id);
		if(exposer.isExposed()) {
			logger.info("exposer={}", exposer);
			long phone = 15623355407L;
			String md5 = exposer.getMd5();
			try {
				SeckillExecution execution = seckillService.executeSeckill(id, phone, md5);
				logger.info("result={}", execution);
			} catch(RepeatKillException e) {
				logger.error(e.getMessage());
			} catch(SeckillCloseException e) {
				logger.error(e.getMessage());
			}
		}
		else {
			//秒杀未开启
			logger.warn("exposer={}", exposer);
		}
	}
	
	@Test
	public void testExportSeckillUrl() throws Exception {
		long id = 1000L;
		Exposer exposer = seckillService.exportSeckillUrl(id);
		logger.info("exposer={}", exposer);
	}
	
	@Test
	public void testExecuteSeckill() throws Exception {
		long id = 1000L;
		long phone = 15623655480L;
		String md5 = "96f58c63d864ecd07475787e10c2c5bc";
		try {
			SeckillExecution execution = seckillService.executeSeckill(id, phone, md5);
			logger.info("result={}", execution);
		} catch(RepeatKillException e) {
			logger.error(e.getMessage());
		} catch(SeckillCloseException e) {
			logger.error(e.getMessage());
		}
	}
	
	@Test
	public void executeSeckillProcedure() {
		long seckillId = 1001;
		long phone = 13652369563l;
		Exposer exposer = seckillService.exportSeckillUrl(seckillId);
		System.out.println(exposer.getMd5());
		if(exposer.isExposed()) {
			String md5 = exposer.getMd5();
			SeckillExecution execution = seckillService.executeSeckillProcedure(seckillId, phone, md5);
			logger.info(execution.getStateInfo());
		}
		
	}
}
