package com.wxmblog.nostalgia;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wxmblog.MsfastNostalgiaApplication;
import com.wxmblog.base.auth.service.MsfConfigService;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.common.enums.user.SysConfigCodeEnum;
import com.wxmblog.nostalgia.common.rest.response.front.payment.PayMoneyResponse;
import com.wxmblog.nostalgia.entity.FrUserEntity;
import com.wxmblog.nostalgia.entity.PayOrderEntity;
import com.wxmblog.nostalgia.service.FrUserService;
import com.wxmblog.nostalgia.service.PayOrderService;
import com.wxmblog.nostalgia.service.impl.WxPayServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信支付集成测试类
 * 测试目标：
 * 1. 验证支付回调接口的幂等性
 * 2. 验证并发回调下金币余额不会被重复增加
 * 3. 验证订单状态和流水的最终一致性
 */
@SpringBootTest(classes = MsfastNostalgiaApplication.class)
@Slf4j
public class WxPayIdempotencyIntegrationTest {

    @Autowired
    private WxPayServiceImpl wxPayService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private FrUserService frUserService;

    @Autowired
    private MsfConfigService msfConfigService;

    private Integer testUserId;
    private String testOutTradeNo;
    private Integer originalGoldBalance;
    private Integer expectedGoldAmount = 100; // 假设每次支付增加100金币
    private Integer testProductNo = 100; // 测试用的商品编号

    private List<Integer> createdOrderIds = new ArrayList<>();
    private List<Integer> createdUserIds = new ArrayList<>();

    /**
     * 测试前置准备：创建测试用户和测试订单
     */
    @BeforeEach
    public void setUp() {
        log.info("========== 开始测试前置准备 ==========");
        
        // 1. 创建测试用户
        FrUserEntity testUser = new FrUserEntity();
        testUser.setNickName("测试用户_" + System.currentTimeMillis());
        testUser.setGoldBalance(0); // 初始金币余额为0
        testUser.setOpenId("test_openid_" + System.currentTimeMillis());
        frUserService.save(testUser);
        testUserId = testUser.getId();
        createdUserIds.add(testUserId);
        originalGoldBalance = testUser.getGoldBalance();
        log.info("创建测试用户，userId={}, 初始金币余额={}", testUserId, originalGoldBalance);

        // 2. 准备测试配置数据（模拟支付菜单配置）
        prepareTestConfig();

        // 3. 创建测试订单
        testOutTradeNo = "TEST_ORDER_" + System.currentTimeMillis();
        PayOrderEntity testOrder = new PayOrderEntity();
        testOrder.setUserId(testUserId);
        testOrder.setOutTradeNo(testOutTradeNo);
        testOrder.setStatus(PayOrderStatusEnum.PRE_PAY);
        testOrder.setTitle("测试支付订单");
        testOrder.setTotalFee(testProductNo);
        testOrder.setProductNo(testProductNo);
        payOrderService.save(testOrder);
        createdOrderIds.add(testOrder.getId());
        log.info("创建测试订单，orderId={}, outTradeNo={}", testOrder.getId(), testOutTradeNo);

        log.info("========== 测试前置准备完成 ==========");
    }

    /**
     * 清理测试数据
     */
    @AfterEach
    public void tearDown() {
        log.info("========== 开始清理测试数据 ==========");
        
        // 清理创建的订单
        if (!createdOrderIds.isEmpty()) {
            Wrapper<PayOrderEntity> orderDeleteWrapper = new QueryWrapper<PayOrderEntity>().lambda()
                    .in(PayOrderEntity::getId, createdOrderIds);
            payOrderService.remove(orderDeleteWrapper);
            log.info("清理测试订单，orderIds={}", createdOrderIds);
        }

        // 清理创建的用户
        if (!createdUserIds.isEmpty()) {
            Wrapper<FrUserEntity> userDeleteWrapper = new QueryWrapper<FrUserEntity>().lambda()
                    .in(FrUserEntity::getId, createdUserIds);
            frUserService.remove(userDeleteWrapper);
            log.info("清理测试用户，userIds={}", createdUserIds);
        }

        createdOrderIds.clear();
        createdUserIds.clear();
        log.info("========== 测试数据清理完成 ==========");
    }

    /**
     * 准备测试配置数据
     */
    private void prepareTestConfig() {
        // 模拟支付菜单配置
        List<PayMoneyResponse> payMenuList = new ArrayList<>();
        PayMoneyResponse payMoneyResponse = new PayMoneyResponse();
        payMoneyResponse.setPrice(testProductNo);
        payMoneyResponse.setAmount(expectedGoldAmount);
        payMenuList.add(payMoneyResponse);
        
        // 由于无法直接操作配置服务，我们将通过模拟的方式处理
        // 在实际测试中，这里应该配置测试环境的支付菜单
        log.info("准备测试配置，商品编号={}, 金币数量={}", testProductNo, expectedGoldAmount);
    }

    /**
     * 测试用例1：验证单次支付回调的正常流程
     * 预期结果：订单状态变为SUCCESS，用户金币余额增加expectedGoldAmount
     */
    @Test
    public void testSinglePaymentCallback() {
        log.info("========== 开始测试：单次支付回调 ==========");

        // 1. 创建回调数据
        Object notifyData = createNotifyUrlData(testOutTradeNo, testUserId);

        // 2. 执行回调处理
        try {
            // 使用反射调用notifyOrder方法（因为它是private的）
            java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
            method.setAccessible(true);
            method.invoke(wxPayService, notifyData);
        } catch (Exception e) {
            log.error("执行回调处理失败", e);
            fail("回调处理异常: " + e.getMessage());
        }

        // 3. 验证订单状态
        PayOrderEntity updatedOrder = payOrderService.getOne(
                new QueryWrapper<PayOrderEntity>().lambda()
                        .eq(PayOrderEntity::getOutTradeNo, testOutTradeNo)
        );
        assertNotNull(updatedOrder, "订单应该存在");
        assertEquals(PayOrderStatusEnum.SUCCESS, updatedOrder.getStatus(), "订单状态应该变为SUCCESS");
        log.info("订单状态验证通过，当前状态={}", updatedOrder.getStatus());

        // 4. 验证用户金币余额
        FrUserEntity updatedUser = frUserService.getById(testUserId);
        assertNotNull(updatedUser, "用户应该存在");
        Integer expectedBalance = originalGoldBalance + expectedGoldAmount;
        assertEquals(expectedBalance, updatedUser.getGoldBalance(), 
                String.format("金币余额应该增加%d，当前余额=%d，预期余额=%d", 
                        expectedGoldAmount, updatedUser.getGoldBalance(), expectedBalance));
        log.info("金币余额验证通过，当前余额={}, 预期余额={}", updatedUser.getGoldBalance(), expectedBalance);

        log.info("========== 测试：单次支付回调完成 ==========");
    }

    /**
     * 测试用例2：验证幂等性 - 重复发送相同的支付回调
     * 预期结果：金币余额只增加一次，订单状态保持SUCCESS
     */
    @Test
    public void testIdempotentPaymentCallback() {
        log.info("========== 开始测试：幂等性 - 重复支付回调 ==========");

        // 1. 创建回调数据
        Object notifyData = createNotifyUrlData(testOutTradeNo, testUserId);

        // 2. 第一次回调处理
        try {
            java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
            method.setAccessible(true);
            method.invoke(wxPayService, notifyData);
            log.info("第一次回调处理完成");
        } catch (Exception e) {
            log.error("第一次回调处理失败", e);
            fail("第一次回调处理异常: " + e.getMessage());
        }

        // 3. 第二次回调处理（重复回调）
        try {
            java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
            method.setAccessible(true);
            method.invoke(wxPayService, notifyData);
            log.info("第二次回调处理完成");
        } catch (Exception e) {
            log.error("第二次回调处理失败", e);
            fail("第二次回调处理异常: " + e.getMessage());
        }

        // 4. 第三次回调处理（重复回调）
        try {
            java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
            method.setAccessible(true);
            method.invoke(wxPayService, notifyData);
            log.info("第三次回调处理完成");
        } catch (Exception e) {
            log.error("第三次回调处理失败", e);
            fail("第三次回调处理异常: " + e.getMessage());
        }

        // 5. 验证最终状态
        PayOrderEntity finalOrder = payOrderService.getOne(
                new QueryWrapper<PayOrderEntity>().lambda()
                        .eq(PayOrderEntity::getOutTradeNo, testOutTradeNo)
        );
        assertNotNull(finalOrder, "订单应该存在");
        assertEquals(PayOrderStatusEnum.SUCCESS, finalOrder.getStatus(), "订单状态应该保持SUCCESS");
        log.info("订单状态验证通过，最终状态={}", finalOrder.getStatus());

        FrUserEntity finalUser = frUserService.getById(testUserId);
        assertNotNull(finalUser, "用户应该存在");
        Integer expectedBalance = originalGoldBalance + expectedGoldAmount;
        assertEquals(expectedBalance, finalUser.getGoldBalance(), 
                String.format("金币余额应该只增加%d，当前余额=%d，预期余额=%d", 
                        expectedGoldAmount, finalUser.getGoldBalance(), expectedBalance));
        log.info("金币余额幂等性验证通过，最终余额={}, 预期余额={}", finalUser.getGoldBalance(), expectedBalance);

        log.info("========== 测试：幂等性 - 重复支付回调完成 ==========");
    }

    /**
     * 测试用例3：验证并发回调下的幂等性
     * 预期结果：即使同时发送多个回调请求，金币余额也只增加一次
     */
    @Test
    public void testConcurrentPaymentCallbacks() throws InterruptedException {
        log.info("========== 开始测试：并发回调幂等性 ==========");

        int threadCount = 10; // 并发线程数
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 1. 创建回调数据
        Object notifyData = createNotifyUrlData(testOutTradeNo, testUserId);

        // 2. 提交并发任务
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    startLatch.await(); // 等待所有线程准备就绪
                    log.info("线程{}开始处理回调", threadIndex);
                    
                    java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
                    method.setAccessible(true);
                    method.invoke(wxPayService, notifyData);
                    
                    successCount.incrementAndGet();
                    log.info("线程{}回调处理成功", threadIndex);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("线程{}回调处理失败", threadIndex, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 3. 同时启动所有线程
        log.info("启动{}个并发线程", threadCount);
        startLatch.countDown();

        // 4. 等待所有线程完成
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "所有线程应该在60秒内完成");
        executorService.shutdown();

        log.info("并发回调处理完成，成功数={}, 失败数={}", successCount.get(), failCount.get());

        // 5. 验证最终状态
        PayOrderEntity finalOrder = payOrderService.getOne(
                new QueryWrapper<PayOrderEntity>().lambda()
                        .eq(PayOrderEntity::getOutTradeNo, testOutTradeNo)
        );
        assertNotNull(finalOrder, "订单应该存在");
        assertEquals(PayOrderStatusEnum.SUCCESS, finalOrder.getStatus(), "订单状态应该保持SUCCESS");
        log.info("订单状态验证通过，最终状态={}", finalOrder.getStatus());

        FrUserEntity finalUser = frUserService.getById(testUserId);
        assertNotNull(finalUser, "用户应该存在");
        Integer expectedBalance = originalGoldBalance + expectedGoldAmount;
        assertEquals(expectedBalance, finalUser.getGoldBalance(), 
                String.format("并发回调下金币余额应该只增加%d，当前余额=%d，预期余额=%d", 
                        expectedGoldAmount, finalUser.getGoldBalance(), expectedBalance));
        log.info("并发回调金币余额验证通过，最终余额={}, 预期余额={}", finalUser.getGoldBalance(), expectedBalance);

        log.info("========== 测试：并发回调幂等性完成 ==========");
    }

    /**
     * 测试用例4：验证多订单并发场景
     * 预期结果：每个订单的金币都正确增加，不会互相干扰
     */
    @Test
    public void testMultipleOrdersConcurrent() throws InterruptedException {
        log.info("========== 开始测试：多订单并发场景 ==========");

        int orderCount = 5; // 订单数量
        List<String> outTradeNoList = new ArrayList<>();
        List<Integer> userIds = new ArrayList<>();

        // 1. 为每个订单创建测试用户和订单
        for (int i = 0; i < orderCount; i++) {
            // 创建用户
            FrUserEntity user = new FrUserEntity();
            user.setNickName("多订单测试用户_" + i);
            user.setGoldBalance(0);
            user.setOpenId("multi_test_openid_" + i + "_" + System.currentTimeMillis());
            frUserService.save(user);
            userIds.add(user.getId());
            createdUserIds.add(user.getId());

            // 创建订单
            String outTradeNo = "MULTI_TEST_ORDER_" + i + "_" + System.currentTimeMillis();
            PayOrderEntity order = new PayOrderEntity();
            order.setUserId(user.getId());
            order.setOutTradeNo(outTradeNo);
            order.setStatus(PayOrderStatusEnum.PRE_PAY);
            order.setTitle("多订单测试订单_" + i);
            order.setTotalFee(testProductNo);
            order.setProductNo(testProductNo);
            payOrderService.save(order);
            outTradeNoList.add(outTradeNo);
            createdOrderIds.add(order.getId());

            log.info("创建订单{}，outTradeNo={}, userId={}", i, outTradeNo, user.getId());
        }

        // 2. 并发执行所有订单的回调
        int threadCount = orderCount;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < orderCount; i++) {
            final int index = i;
            final String outTradeNo = outTradeNoList.get(i);
            final Integer userId = userIds.get(i);
            
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    log.info("订单{}开始处理回调", index);
                    
                    Object notifyData = createNotifyUrlData(outTradeNo, userId);
                    java.lang.reflect.Method method = WxPayServiceImpl.class.getDeclaredMethod("notifyOrder", Object.class);
                    method.setAccessible(true);
                    method.invoke(wxPayService, notifyData);
                    
                    log.info("订单{}回调处理成功", index);
                } catch (Exception e) {
                    log.error("订单{}回调处理失败", index, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 3. 启动并发处理
        startLatch.countDown();
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "所有订单应该在60秒内完成");
        executorService.shutdown();

        // 4. 验证所有订单和用户状态
        for (int i = 0; i < orderCount; i++) {
            String outTradeNo = outTradeNoList.get(i);
            Integer userId = userIds.get(i);

            // 验证订单状态
            PayOrderEntity order = payOrderService.getOne(
                    new QueryWrapper<PayOrderEntity>().lambda()
                            .eq(PayOrderEntity::getOutTradeNo, outTradeNo)
            );
            assertNotNull(order, "订单" + i + "应该存在");
            assertEquals(PayOrderStatusEnum.SUCCESS, order.getStatus(), "订单" + i + "状态应该为SUCCESS");

            // 验证用户金币余额
            FrUserEntity user = frUserService.getById(userId);
            assertNotNull(user, "用户" + i + "应该存在");
            assertEquals(expectedGoldAmount, user.getGoldBalance(), 
                    "用户" + i + "金币余额应该为" + expectedGoldAmount);

            log.info("订单{}验证通过，订单状态={}, 用户金币={}", i, order.getStatus(), user.getGoldBalance());
        }

        log.info("========== 测试：多订单并发场景完成 ==========");
    }

    /**
     * 创建模拟的NotifyUrlData对象
     * 由于NotifyUrlData在base-pay模块中，我们使用JSONObject模拟
     */
    private Object createNotifyUrlData(String outTradeNo, Integer userId) {
        // 使用JSONObject模拟NotifyUrlData
        com.alibaba.fastjson.JSONObject notifyData = new com.alibaba.fastjson.JSONObject();
        notifyData.put("outTradeNo", outTradeNo);
        
        // 构建attach数据
        com.alibaba.fastjson.JSONObject attach = new com.alibaba.fastjson.JSONObject();
        attach.put("userId", userId);
        notifyData.put("attach", attach.toJSONString());
        
        // 设置其他必要字段
        notifyData.put("transactionId", "TEST_TRANSACTION_" + System.currentTimeMillis());
        notifyData.put("totalFee", 1);
        
        log.info("创建回调数据，outTradeNo={}, userId={}", outTradeNo, userId);
        return notifyData;
    }
}
