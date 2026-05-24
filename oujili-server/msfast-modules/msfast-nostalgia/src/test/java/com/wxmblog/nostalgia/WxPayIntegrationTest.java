package com.wxmblog.nostalgia;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wxmblog.base.pay.common.rest.response.NotifyUrlData;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.common.enums.user.SysConfigCodeEnum;
import com.wxmblog.nostalgia.common.rest.response.front.payment.PayMoneyResponse;
import com.wxmblog.nostalgia.entity.FrUserEntity;
import com.wxmblog.nostalgia.entity.PayOrderEntity;
import com.wxmblog.nostalgia.service.FrUserService;
import com.wxmblog.nostalgia.service.PayOrderService;
import com.wxmblog.nostalgia.service.impl.WxPayServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MsfastNostalgiaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Slf4j
public class WxPayIntegrationTest {

    @Autowired
    private WxPayServiceImpl wxPayService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private FrUserService frUserService;

    private static final Integer TEST_USER_ID = 1;
    private static final Integer TEST_PRODUCT_NO = 1;
    private static final String TEST_OUT_TRADE_NO = "TEST_TRADE_NO_" + System.currentTimeMillis();
    private static final Integer GOLD_AMOUNT = 100;
    private static final Integer INITIAL_GOLD_BALANCE = 500;

    private Integer testUserId;
    private String outTradeNo;

    @BeforeEach
    public void setUp() {
        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        if (user == null) {
            user = new FrUserEntity();
            user.setId(TEST_USER_ID);
            user.setGoldBalance(INITIAL_GOLD_BALANCE);
            frUserService.save(user);
        } else {
            user.setGoldBalance(INITIAL_GOLD_BALANCE);
            frUserService.saveOrUpdate(user);
        }
        testUserId = TEST_USER_ID;
        outTradeNo = "TEST_TRADE_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
    }

    @AfterEach
    public void tearDown() {
        QueryWrapper<PayOrderEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("out_trade_no", outTradeNo);
        payOrderService.remove(wrapper);
    }

    @Test
    @Order(1)
    @DisplayName("测试单次支付回调 - 验证基本流程")
    public void testSingleNotifySuccess() {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;
        log.info("回调前用户金币余额: {}", balanceBefore);

        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);
        assertEquals(PayOrderStatusEnum.PRE_PAY, order.getStatus(), "订单初始状态应为预支付");

        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);
        wxPayService.appletNotifyUrl(notifyRequest);

        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;
        log.info("回调后用户金币余额: {}", balanceAfter);

        PayOrderEntity updatedOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, outTradeNo)
        );
        assertEquals(PayOrderStatusEnum.SUCCESS, updatedOrder.getStatus(), "订单状态应变为成功");
        assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfter, "金币余额应增加");
    }

    @Test
    @Order(2)
    @DisplayName("测试相同交易流水号重复回调 - 验证幂等性")
    public void testDuplicateNotifyIdempotency() {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;
        log.info("重复回调前用户金币余额: {}", balanceBefore);

        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);

        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);

        wxPayService.appletNotifyUrl(notifyRequest);

        FrUserEntity userAfterFirst = frUserService.getById(testUserId);
        int balanceAfterFirst = userAfterFirst.getGoldBalance() != null ? userAfterFirst.getGoldBalance() : 0;
        log.info("第一次回调后用户金币余额: {}", balanceAfterFirst);

        wxPayService.appletNotifyUrl(notifyRequest);

        FrUserEntity userAfterSecond = frUserService.getById(testUserId);
        int balanceAfterSecond = userAfterSecond.getGoldBalance() != null ? userAfterSecond.getGoldBalance() : 0;
        log.info("第二次回调后用户金币余额: {}", balanceAfterSecond);

        assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfterFirst, "第一次回调后金币余额应增加");
        assertEquals(balanceAfterFirst, balanceAfterSecond, "重复回调后金币余额不应再增加");

        PayOrderEntity finalOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, outTradeNo)
        );
        assertEquals(PayOrderStatusEnum.SUCCESS, finalOrder.getStatus(), "订单状态应为成功");
    }

    @Test
    @Order(3)
    @DisplayName("测试三次相同回调 - 验证状态检查幂等性")
    public void testTripleDuplicateNotify() {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;

        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);
        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);

        wxPayService.appletNotifyUrl(notifyRequest);
        wxPayService.appletNotifyUrl(notifyRequest);
        wxPayService.appletNotifyUrl(notifyRequest);

        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;

        assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfter, "三次回调后金币余额只应增加一次");
    }

    @Test
    @Order(4)
    @DisplayName("测试并发回调 - 验证金币余额不被重复添加")
    public void testConcurrentNotifyNoDoubleAdd() throws InterruptedException, ExecutionException {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;
        log.info("并发回调前用户金币余额: {}", balanceBefore);

        String concurrentTradeNo = "CONCURRENT_TRADE_" + System.currentTimeMillis();
        PayOrderEntity order = createTestOrder(concurrentTradeNo, testUserId);
        assertEquals(PayOrderStatusEnum.PRE_PAY, order.getStatus());

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<Boolean>> futures = new ArrayList<>();

        NotifyUrlData notifyRequest = createNotifyRequest(concurrentTradeNo, testUserId);

        for (int i = 0; i < threadCount; i++) {
            Future<Boolean> future = executorService.submit(() -> {
                try {
                    wxPayService.appletNotifyUrl(notifyRequest);
                    successCount.incrementAndGet();
                    return true;
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("并发回调异常: {}", e.getMessage());
                    return false;
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("获取结果异常: {}", e.getMessage());
            }
        }

        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;
        log.info("并发回调后用户金币余额: {}, 成功次数: {}, 失败次数: {}", balanceAfter, successCount.get(), failCount.get());

        assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfter,
                "并发回调后金币余额只应增加一次，当前期望: " + (balanceBefore + GOLD_AMOUNT) + ", 实际: " + balanceAfter);

        PayOrderEntity finalOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, concurrentTradeNo)
        );
        assertEquals(PayOrderStatusEnum.SUCCESS, finalOrder.getStatus(), "订单状态应为成功");
        assertEquals(1, successCount.get(), "只有一次回调应该成功处理订单");
    }

    @Test
    @Order(5)
    @DisplayName("测试高并发场景 - 多个不同订单并发处理")
    public void testHighConcurrencyMultipleOrders() throws InterruptedException {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;

        int orderCount = 5;
        int threadCount = orderCount * 3;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> tradeNos = new CopyOnWriteArrayList<>();

        for (int i = 0; i < orderCount; i++) {
            String tradeNo = "PARALLEL_TRADE_" + System.currentTimeMillis() + "_" + i;
            tradeNos.add(tradeNo);
            createTestOrder(tradeNo, testUserId);

            NotifyUrlData notifyRequest = createNotifyRequest(tradeNo, testUserId);

            for (int j = 0; j < 3; j++) {
                final String finalTradeNo = tradeNo;
                final NotifyUrlData finalRequest = createNotifyRequest(finalTradeNo, testUserId);
                executorService.submit(() -> {
                    try {
                        wxPayService.appletNotifyUrl(finalRequest);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;
        int expectedIncrease = orderCount * GOLD_AMOUNT;

        assertEquals(balanceBefore + expectedIncrease, balanceAfter,
                "多个不同订单并发处理后，金币余额应正确增加");

        for (String tradeNo : tradeNos) {
            PayOrderEntity order = payOrderService.getBaseMapper().selectOne(
                    new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, tradeNo)
            );
            assertNotNull(order, "订单应存在");
            assertEquals(PayOrderStatusEnum.SUCCESS, order.getStatus(), "订单状态应为成功");
        }
    }

    @Test
    @Order(6)
    @DisplayName("测试未支付订单回调 - 验证订单不存在时的处理")
    public void testNotifyNonExistentOrder() {
        String nonExistentTradeNo = "NON_EXISTENT_" + System.currentTimeMillis();
        NotifyUrlData notifyRequest = createNotifyRequest(nonExistentTradeNo, testUserId);

        assertDoesNotThrow(() -> wxPayService.appletNotifyUrl(notifyRequest),
                "不存在的订单回调不应抛出异常");

        FrUserEntity user = frUserService.getById(testUserId);
        int balance = user.getGoldBalance() != null ? user.getGoldBalance() : 0;
        log.info("不存在的订单回调后金币余额: {}", balance);
    }

    @Test
    @Order(7)
    @DisplayName("测试数据库事务一致性 - 验证订单状态和金豆余额原子性")
    public void testTransactionConsistency() {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;

        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);

        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);
        wxPayService.appletNotifyUrl(notifyRequest);

        PayOrderEntity updatedOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, outTradeNo)
        );
        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;

        if (PayOrderStatusEnum.SUCCESS.equals(updatedOrder.getStatus())) {
            assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfter,
                    "订单状态为成功时，金币余额应正确增加");
        } else {
            assertEquals(balanceBefore, balanceAfter,
                    "订单状态不为成功时，金币余额不应变化");
        }
    }

    @Test
    @Order(8)
    @DisplayName("测试连续快速多次回调 - 验证状态转换期间的处理")
    public void testRapidConsecutiveNotify() {
        FrUserEntity userBefore = frUserService.getById(testUserId);
        int balanceBefore = userBefore.getGoldBalance() != null ? userBefore.getGoldBalance() : 0;

        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);
        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);

        for (int i = 0; i < 5; i++) {
            wxPayService.appletNotifyUrl(notifyRequest);
        }

        FrUserEntity userAfter = frUserService.getById(testUserId);
        int balanceAfter = userAfter.getGoldBalance() != null ? userAfter.getGoldBalance() : 0;

        assertEquals(balanceBefore + GOLD_AMOUNT, balanceAfter, "快速连续回调后金币余额只应增加一次");
    }

    @Test
    @Order(9)
    @DisplayName("测试订单状态流转 - 验证完整生命周期")
    public void testOrderStatusLifecycle() {
        PayOrderEntity order = createTestOrder(outTradeNo, testUserId);
        assertEquals(PayOrderStatusEnum.PRE_PAY, order.getStatus(), "初始状态应为预支付");

        NotifyUrlData notifyRequest = createNotifyRequest(outTradeNo, testUserId);
        wxPayService.appletNotifyUrl(notifyRequest);

        PayOrderEntity updatedOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, outTradeNo)
        );
        assertEquals(PayOrderStatusEnum.SUCCESS, updatedOrder.getStatus(), "回调后状态应为成功");

        wxPayService.appletNotifyUrl(notifyRequest);

        PayOrderEntity finalOrder = payOrderService.getBaseMapper().selectOne(
                new QueryWrapper<PayOrderEntity>().lambda().eq(PayOrderEntity::getOutTradeNo, outTradeNo)
        );
        assertEquals(PayOrderStatusEnum.SUCCESS, finalOrder.getStatus(), "再次回调后状态仍应为成功");
    }

    private PayOrderEntity createTestOrder(String outTradeNo, Integer userId) {
        PayOrderEntity order = new PayOrderEntity();
        order.setOutTradeNo(outTradeNo);
        order.setStatus(PayOrderStatusEnum.PRE_PAY);
        order.setTitle("思君币");
        order.setTotalFee(1);
        order.setUserId(userId);
        order.setProductNo(TEST_PRODUCT_NO);
        payOrderService.save(order);
        return order;
    }

    private NotifyUrlData createNotifyRequest(String outTradeNo, Integer userId) {
        NotifyUrlData request = new NotifyUrlData();
        request.setOutTradeNo(outTradeNo);
        request.setAttach(JSON.toJSONString(java.util.Collections.singletonMap("userId", userId)));
        request.setTransactionId("WX_TRANSACTION_" + System.currentTimeMillis());
        return request;
    }
}