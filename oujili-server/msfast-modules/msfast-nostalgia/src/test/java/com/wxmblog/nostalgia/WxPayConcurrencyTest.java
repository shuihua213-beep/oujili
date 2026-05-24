package com.wxmblog.nostalgia;

import com.alibaba.fastjson.JSON;
import com.wxmblog.MsfastNostalgiaApplication;
import com.wxmblog.base.pay.common.rest.response.NotifyUrlData;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.entity.FrUserEntity;
import com.wxmblog.nostalgia.entity.PayOrderEntity;
import com.wxmblog.nostalgia.service.FrUserService;
import com.wxmblog.nostalgia.service.PayOrderService;
import com.wxmblog.nostalgia.service.impl.WxPayServiceImpl;
import com.wxmblog.base.auth.service.MsfConfigService;
import com.wxmblog.nostalgia.common.enums.user.SysConfigCodeEnum;
import com.wxmblog.nostalgia.common.rest.response.front.payment.PayMoneyResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(classes = MsfastNostalgiaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WxPayConcurrencyTest {

    @Autowired
    private WxPayServiceImpl wxPayService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private FrUserService frUserService;

    @MockBean
    private MsfConfigService msfConfigService;

    @Test
    public void testConcurrentNotifyUrl() throws InterruptedException {
        // 1. Prepare configuration data
        // Insert or update msfConfigService to return some PayMoneyResponse list
        List<PayMoneyResponse> moneyResponseList = new ArrayList<>();
        PayMoneyResponse pmr = new PayMoneyResponse();
        pmr.setPrice(100);
        pmr.setAmount(50); // Adds 50 gold coins
        moneyResponseList.add(pmr);
        Mockito.when(msfConfigService.getValueByCode(SysConfigCodeEnum.payMenuList.name())).thenReturn(JSON.toJSONString(moneyResponseList));

        // 2. Prepare user data
        FrUserEntity user = new FrUserEntity();
        user.setGoldBalance(100); // initial balance
        frUserService.save(user);

        // 3. Prepare order data
        String outTradeNo = UUID.randomUUID().toString();
        PayOrderEntity order = new PayOrderEntity();
        order.setOutTradeNo(outTradeNo);
        order.setStatus(PayOrderStatusEnum.PRE_PAY);
        order.setUserId(user.getId());
        order.setProductNo(100); 
        payOrderService.save(order);

        // 4. Prepare NotifyUrlData
        NotifyUrlData notifyData = new NotifyUrlData();
        notifyData.setOutTradeNo(outTradeNo);
        Map<String, Object> attach = new HashMap<>();
        attach.put("userId", user.getId());
        notifyData.setAttach(JSON.toJSONString(attach));

        // 5. Concurrency Test
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    latch.await();
                    wxPayService.appletNotifyUrl(notifyData);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        latch.countDown();
        endLatch.await();

        // 6. Assertions
        FrUserEntity updatedUser = frUserService.getById(user.getId());
        PayOrderEntity updatedOrder = payOrderService.getBaseMapper().selectById(order.getId());

        System.out.println("Final Gold Balance: " + updatedUser.getGoldBalance());
        
        // At least the order status should be SUCCESS
        Assertions.assertEquals(PayOrderStatusEnum.SUCCESS, updatedOrder.getStatus());
        
        // Assert idempotency: balance should only be incremented ONCE (100 + 50 = 150)
        Assertions.assertEquals(150, updatedUser.getGoldBalance(), "The gold balance should only be updated exactly once.");
    }
}
