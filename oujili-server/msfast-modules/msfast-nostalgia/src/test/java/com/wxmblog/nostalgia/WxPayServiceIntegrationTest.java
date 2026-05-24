package com.wxmblog.nostalgia;

import com.alibaba.fastjson.JSON;
import com.wxmblog.base.auth.service.MsfConfigService;
import com.wxmblog.base.common.enums.FrUserStatusEnum;
import com.wxmblog.base.common.enums.GenderEnum;
import com.wxmblog.base.pay.common.rest.response.NotifyUrlData;
import com.wxmblog.nostalgia.common.enums.user.EducationalTypeEnum;
import com.wxmblog.nostalgia.common.enums.user.HighestEducationEnum;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.common.enums.user.UserTypeEnum;
import com.wxmblog.nostalgia.common.rest.response.front.payment.PayMoneyResponse;
import com.wxmblog.nostalgia.entity.FrUserEntity;
import com.wxmblog.nostalgia.entity.PayOrderEntity;
import com.wxmblog.nostalgia.service.FrUserService;
import com.wxmblog.nostalgia.service.PayOrderService;
import com.wxmblog.nostalgia.service.impl.WxPayServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

@ActiveProfiles("test")
@SpringBootTest(classes = MsfastNostalgiaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class WxPayServiceIntegrationTest {

    private static final String TEST_OUT_TRADE_NO = "INTEGRATION_TEST_ORDER_001";
    private static final String TEST_TRANSACTION_ID = "WX_TX_20240523_001";
    private static final Integer TEST_USER_ID = 999;
    private static final Integer TEST_PRODUCT_NO = 1;
    private static final Integer PRODUCT_PRICE = 1000;
    private static final Integer GOLD_AMOUNT = 100;
    private static final Integer INITIAL_GOLD_BALANCE = 500;

    @Autowired
    private WxPayServiceImpl wxPayService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private FrUserService frUserService;

    @MockBean
    private MsfConfigService msfConfigService;

    @MockBean
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        cleanupTestData();
        createTestUser();
        preparePayMenuConfig();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    @DisplayName("幂等性验证：顺序发送相同 outTradeNo 的多次回调，金币仅增加一次，订单状态正确")
    void testIdempotent_SequentialDuplicateCallback() {
        createPayOrder(TEST_OUT_TRADE_NO, TEST_PRODUCT_NO, TEST_USER_ID);

        NotifyUrlData notifyData = buildNotifyUrlData(TEST_OUT_TRADE_NO, TEST_TRANSACTION_ID, TEST_USER_ID);

        wxPayService.appletNotifyUrl(notifyData);
        wxPayService.appletNotifyUrl(notifyData);
        wxPayService.appletNotifyUrl(notifyData);

        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        assertThat(user.getGoldBalance()).as("gold_balance 应仅增加一次")
                .isEqualTo(INITIAL_GOLD_BALANCE + GOLD_AMOUNT);

        PayOrderEntity order = queryOrderByOutTradeNo(TEST_OUT_TRADE_NO);
        assertThat(order.getStatus()).as("订单状态应为 SUCCESS")
                .isEqualTo(PayOrderStatusEnum.SUCCESS);

        long orderCount = payOrderService.count(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PayOrderEntity>()
                        .eq("out_trade_no", TEST_OUT_TRADE_NO)
        );
        assertThat(orderCount).as("同一 outTradeNo 只应有一条订单记录").isEqualTo(1);
    }

    @Test
    @DisplayName("幂等性验证：对已 SUCCESS 的订单再次回调，不修改金币和订单状态")
    void testIdempotent_CallbackAfterSuccessDoesNotAlterState() {
        createPayOrder(TEST_OUT_TRADE_NO, TEST_PRODUCT_NO, TEST_USER_ID);

        NotifyUrlData notifyData = buildNotifyUrlData(TEST_OUT_TRADE_NO, TEST_TRANSACTION_ID, TEST_USER_ID);

        wxPayService.appletNotifyUrl(notifyData);

        FrUserEntity userAfterFirst = frUserService.getById(TEST_USER_ID);
        int goldAfterFirst = userAfterFirst.getGoldBalance();

        wxPayService.appletNotifyUrl(notifyData);

        FrUserEntity userAfterSecond = frUserService.getById(TEST_USER_ID);
        assertThat(userAfterSecond.getGoldBalance()).as("已支付成功后再次回调不应修改金币")
                .isEqualTo(goldAfterFirst);

        PayOrderEntity order = queryOrderByOutTradeNo(TEST_OUT_TRADE_NO);
        assertThat(order.getStatus()).as("订单状态应保持 SUCCESS")
                .isEqualTo(PayOrderStatusEnum.SUCCESS);
    }

    @Test
    @DisplayName("并发幂等性验证：并发发送相同 outTradeNo 的多个回调，金币余额绝不会被重复添加")
    void testIdempotent_ConcurrentCallbackSameOrder() throws Exception {
        createPayOrder(TEST_OUT_TRADE_NO, TEST_PRODUCT_NO, TEST_USER_ID);

        NotifyUrlData notifyData = buildNotifyUrlData(TEST_OUT_TRADE_NO, TEST_TRANSACTION_ID, TEST_USER_ID);

        int concurrentThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(concurrentThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    wxPayService.appletNotifyUrl(notifyData);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("所有并发线程应在超时前完成").isTrue();

        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        assertThat(user.getGoldBalance()).as("并发回调下金币余额不应被重复添加")
                .isEqualTo(INITIAL_GOLD_BALANCE + GOLD_AMOUNT);

        PayOrderEntity order = queryOrderByOutTradeNo(TEST_OUT_TRADE_NO);
        assertThat(order.getStatus()).as("订单状态应为 SUCCESS")
                .isEqualTo(PayOrderStatusEnum.SUCCESS);
    }

    @Test
    @DisplayName("并发不同订单验证：并发处理不同 outTradeNo 的订单，每个订单正确增加金币，总额正确")
    void testConcurrent_DifferentOrdersBalanceCorrectness() throws Exception {
        int orderCount = 5;
        List<String> outTradeNos = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            String outTradeNo = "INTEGRATION_CONCURRENT_ORDER_" + i;
            outTradeNos.add(outTradeNo);
            createPayOrder(outTradeNo, TEST_PRODUCT_NO, TEST_USER_ID);
        }

        int concurrentThreads = orderCount;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(concurrentThreads);

        for (int i = 0; i < orderCount; i++) {
            final String outTradeNo = outTradeNos.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    NotifyUrlData notifyData = buildNotifyUrlData(outTradeNo, "TX_" + outTradeNo, TEST_USER_ID);
                    wxPayService.appletNotifyUrl(notifyData);
                } catch (Exception e) {
                    // Expected: optimistic lock may cause some to fail, but final state must be correct
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("所有并发线程应在超时前完成").isTrue();

        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        int expectedGold = INITIAL_GOLD_BALANCE + orderCount * GOLD_AMOUNT;
        assertThat(user.getGoldBalance()).as("金币余额应为 初始 + 所有订单的金币总和")
                .isEqualTo(expectedGold);

        for (String outTradeNo : outTradeNos) {
            PayOrderEntity order = queryOrderByOutTradeNo(outTradeNo);
            assertThat(order).as("订单 [%s] 应存在", outTradeNo).isNotNull();
            assertThat(order.getStatus()).as("订单 [%s] 状态应为 SUCCESS", outTradeNo)
                    .isEqualTo(PayOrderStatusEnum.SUCCESS);
        }
    }

    @Test
    @DisplayName("数据库流水最终一致性验证：回调完成后订单记录和用户金币余额数据一致")
    void testDatabaseConsistency_AfterPayment() {
        createPayOrder(TEST_OUT_TRADE_NO, TEST_PRODUCT_NO, TEST_USER_ID);

        NotifyUrlData notifyData = buildNotifyUrlData(TEST_OUT_TRADE_NO, TEST_TRANSACTION_ID, TEST_USER_ID);
        wxPayService.appletNotifyUrl(notifyData);

        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        PayOrderEntity order = queryOrderByOutTradeNo(TEST_OUT_TRADE_NO);

        assertThat(order).as("支付订单应存在").isNotNull();
        assertThat(order.getUserId()).as("订单 userId 应与用户 ID 一致")
                .isEqualTo(TEST_USER_ID);
        assertThat(order.getStatus()).as("订单状态应为 SUCCESS")
                .isEqualTo(PayOrderStatusEnum.SUCCESS);
        assertThat(order.getOutTradeNo()).as("订单 outTradeNo 应匹配")
                .isEqualTo(TEST_OUT_TRADE_NO);

        assertThat(user.getGoldBalance()).as("用户金币余额应为 初始金币 + 产品金币")
                .isEqualTo(INITIAL_GOLD_BALANCE + GOLD_AMOUNT);
        assertThat(user.getId()).as("用户 ID 应一致").isEqualTo(TEST_USER_ID);
    }

    @Test
    @DisplayName("数据库流水最终一致性验证：多笔不同订单支付后，总计一致")
    void testDatabaseConsistency_MultipleOrdersTotalConsistency() {
        List<String> outTradeNos = Arrays.asList(
                "INTEGRATION_CONSISTENCY_1",
                "INTEGRATION_CONSISTENCY_2",
                "INTEGRATION_CONSISTENCY_3"
        );

        for (String outTradeNo : outTradeNos) {
            createPayOrder(outTradeNo, TEST_PRODUCT_NO, TEST_USER_ID);
        }

        for (String outTradeNo : outTradeNos) {
            NotifyUrlData notifyData = buildNotifyUrlData(outTradeNo, "TX_" + outTradeNo, TEST_USER_ID);
            wxPayService.appletNotifyUrl(notifyData);
        }

        FrUserEntity user = frUserService.getById(TEST_USER_ID);
        int expectedGold = INITIAL_GOLD_BALANCE + outTradeNos.size() * GOLD_AMOUNT;
        assertThat(user.getGoldBalance()).as("金币余额总计应一致")
                .isEqualTo(expectedGold);

        List<PayOrderEntity> orders = payOrderService.list(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PayOrderEntity>()
                        .eq("user_id", TEST_USER_ID)
        );
        assertThat(orders).as("应有 %d 条订单记录", outTradeNos.size())
                .hasSize(outTradeNos.size());

        Set<String> successOrderNos = orders.stream()
                .filter(o -> PayOrderStatusEnum.SUCCESS.equals(o.getStatus()))
                .map(PayOrderEntity::getOutTradeNo)
                .collect(Collectors.toSet());
        assertThat(successOrderNos).as("所有订单均应为 SUCCESS 状态")
                .containsExactlyInAnyOrderElementsOf(outTradeNos);
    }

    private FrUserEntity createTestUser() {
        FrUserEntity user = new FrUserEntity();
        user.setId(TEST_USER_ID);
        user.setOpenId("test_open_id_wx_pay");
        user.setStatus(FrUserStatusEnum.ENABLE);
        user.setNickName("支付测试用户");
        user.setHeadPortrait("https://example.com/avatar.png");
        user.setBirthday(new Date());
        user.setHeight(175);
        user.setProfession("软件工程师");
        user.setGender(GenderEnum.MALE);
        user.setSchool("测试大学");
        user.setEducation(HighestEducationEnum.Undergraduate);
        user.setAboutMe("测试用的关于我");
        user.setInterest("编程");
        user.setLoveRequirement("测试择偶要求");
        user.setGoldBalance(INITIAL_GOLD_BALANCE);
        user.setUserType(UserTypeEnum.Normal);
        user.setEducationalType(EducationalTypeEnum.FullTime);
        user.setDelFlag(0);
        user.setVersion(0);
        frUserService.save(user);
        return user;
    }

    private PayOrderEntity createPayOrder(String outTradeNo, Integer productNo, Integer userId) {
        PayOrderEntity order = new PayOrderEntity();
        order.setUserId(userId);
        order.setOutTradeNo(outTradeNo);
        order.setTotalFee(new BigDecimal(PRODUCT_PRICE).intValue());
        order.setStatus(PayOrderStatusEnum.PRE_PAY);
        order.setProductNo(productNo);
        order.setDelFlag(0);
        order.setVersion(0);
        payOrderService.save(order);
        return order;
    }

    private void preparePayMenuConfig() {
        PayMoneyResponse payMoney = new PayMoneyResponse();
        payMoney.setPrice(TEST_PRODUCT_NO);
        payMoney.setAmount(GOLD_AMOUNT);

        List<PayMoneyResponse> payMenuList = Collections.singletonList(payMoney);
        String payMenuJson = JSON.toJSONString(payMenuList);
        Mockito.when(msfConfigService.getValueByCode(anyString())).thenReturn(payMenuJson);
    }

    private NotifyUrlData buildNotifyUrlData(String outTradeNo, String transactionId, Integer userId) {
        NotifyUrlData mockData = Mockito.mock(NotifyUrlData.class);
        Mockito.when(mockData.getOutTradeNo()).thenReturn(outTradeNo);
        Mockito.when(mockData.getTransactionId()).thenReturn(transactionId);

        Map<String, Object> attachMap = new HashMap<>();
        attachMap.put("userId", userId);
        Mockito.when(mockData.getAttach()).thenReturn(JSON.toJSONString(attachMap));

        return mockData;
    }

    private PayOrderEntity queryOrderByOutTradeNo(String outTradeNo) {
        return payOrderService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PayOrderEntity>()
                        .eq("out_trade_no", outTradeNo)
        );
    }

    private void cleanupTestData() {
        payOrderService.remove(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PayOrderEntity>()
                        .eq("user_id", TEST_USER_ID)
        );
        frUserService.removeById(TEST_USER_ID);
    }
}