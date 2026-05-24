package com.wxmblog.nostalgia.service.impl;

import com.alibaba.fastjson.JSON;
import com.wxmblog.base.auth.service.MsfConfigService;
import com.wxmblog.base.pay.common.rest.response.NotifyUrlData;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.common.rest.response.front.payment.PayMoneyResponse;
import com.wxmblog.nostalgia.dao.PayOrderDao;
import com.wxmblog.nostalgia.entity.FrUserEntity;
import com.wxmblog.nostalgia.service.FrUserService;
import com.wxmblog.nostalgia.service.PayOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = WxPayNotifyIdempotencyIntegrationTest.TestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:wxpay_notify_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.main.allow-bean-definition-overriding=true",
                "logging.level.root=WARN"
        }
)
class WxPayNotifyIdempotencyIntegrationTest {

    private static final Integer USER_ID = 1001;
    private static final Integer INITIAL_GOLD_BALANCE = 10;
    private static final Integer PRODUCT_NO = 99;
    private static final Integer PRODUCT_AMOUNT = 30;
    private static final String OUT_TRADE_NO = "WX-ORDER-001";

    @Autowired
    private WxPayServiceImpl wxPayService;

    @Autowired
    private PayOrderService payOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private MsfConfigService msfConfigService;

    @MockBean
    private FrUserService frUserService;

    @BeforeEach
    void setUp() {
        createTablesIfNecessary();
        jdbcTemplate.execute("delete from pay_order");
        jdbcTemplate.execute("delete from fr_user");
        mockPayMenu();
        mockFrUserPersistence();
    }

    @Test
    void markSuccessIfPrePayShouldBeIdempotent() {
        insertUser(USER_ID, INITIAL_GOLD_BALANCE);
        insertPayOrder(OUT_TRADE_NO, USER_ID, PRODUCT_NO, PayOrderStatusEnum.PRE_PAY);

        boolean firstUpdate = payOrderService.markSuccessIfPrePay(OUT_TRADE_NO);
        boolean secondUpdate = payOrderService.markSuccessIfPrePay(OUT_TRADE_NO);

        assertThat(firstUpdate).isTrue();
        assertThat(secondUpdate).isFalse();
        assertThat(queryOrderStatus(OUT_TRADE_NO)).isEqualTo(PayOrderStatusEnum.SUCCESS.name());
        assertThat(countPayOrderByOutTradeNo(OUT_TRADE_NO)).isEqualTo(1);
    }

    @Test
    void duplicateNotifyShouldOnlyIncreaseGoldBalanceOnce() {
        insertUser(USER_ID, INITIAL_GOLD_BALANCE);
        insertPayOrder(OUT_TRADE_NO, USER_ID, PRODUCT_NO, PayOrderStatusEnum.PRE_PAY);
        NotifyUrlData request = buildNotifyRequest(OUT_TRADE_NO, USER_ID);

        wxPayService.appletNotifyUrl(request);
        wxPayService.appletNotifyUrl(request);
        wxPayService.appletNotifyUrl(request);

        assertThat(queryGoldBalance(USER_ID)).isEqualTo(INITIAL_GOLD_BALANCE + PRODUCT_AMOUNT);
        assertThat(queryOrderStatus(OUT_TRADE_NO)).isEqualTo(PayOrderStatusEnum.SUCCESS.name());
        assertThat(countPayOrderByOutTradeNo(OUT_TRADE_NO)).isEqualTo(1);
        assertThat(countPayOrderByStatus(PayOrderStatusEnum.PRE_PAY)).isZero();
        assertThat(countPayOrderByStatus(PayOrderStatusEnum.SUCCESS)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateNotifyShouldKeepOrderAndBalanceConsistent() throws Exception {
        insertUser(USER_ID, INITIAL_GOLD_BALANCE);
        insertPayOrder(OUT_TRADE_NO, USER_ID, PRODUCT_NO, PayOrderStatusEnum.PRE_PAY);
        NotifyUrlData request = buildNotifyRequest(OUT_TRADE_NO, USER_ID);
        int threadCount = 24;
        ExecutorService executorService = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        startLatch.await(5, TimeUnit.SECONDS);
                        wxPayService.appletNotifyUrl(request);
                    } catch (Throwable throwable) {
                        failures.add(throwable);
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            boolean finished = finishLatch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();
            boolean terminated = executorService.awaitTermination(10, TimeUnit.SECONDS);

            assertThat(finished).isTrue();
            assertThat(terminated).isTrue();
            assertThat(failures).isEmpty();
            assertThat(queryGoldBalance(USER_ID)).isEqualTo(INITIAL_GOLD_BALANCE + PRODUCT_AMOUNT);
            assertThat(queryOrderStatus(OUT_TRADE_NO)).isEqualTo(PayOrderStatusEnum.SUCCESS.name());
            assertThat(countPayOrderByOutTradeNo(OUT_TRADE_NO)).isEqualTo(1);
            assertThat(countPayOrderByStatus(PayOrderStatusEnum.PRE_PAY)).isZero();
            assertThat(countPayOrderByStatus(PayOrderStatusEnum.SUCCESS)).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    private void mockPayMenu() {
        PayMoneyResponse payMoneyResponse = new PayMoneyResponse();
        payMoneyResponse.setPrice(PRODUCT_NO);
        payMoneyResponse.setAmount(PRODUCT_AMOUNT);
        Mockito.when(msfConfigService.getValueByCode(ArgumentMatchers.anyString()))
                .thenReturn(JSON.toJSONString(Collections.singletonList(payMoneyResponse)));
    }

    private void mockFrUserPersistence() {
        Mockito.when(frUserService.getById(ArgumentMatchers.any(Serializable.class)))
                .thenAnswer(invocation -> {
                    Integer userId = (Integer) invocation.getArgument(0);
                    List<FrUserEntity> users = jdbcTemplate.query(
                            "select id, gold_balance from fr_user where id = ?",
                            (rs, rowNum) -> {
                                FrUserEntity user = new FrUserEntity();
                                user.setId(rs.getLong("id"));
                                user.setGoldBalance(rs.getInt("gold_balance"));
                                return user;
                            },
                            userId
                    );
                    return users.isEmpty() ? null : users.get(0);
                });
        Mockito.when(frUserService.saveOrUpdate(ArgumentMatchers.any(FrUserEntity.class)))
                .thenAnswer(invocation -> {
                    FrUserEntity user = invocation.getArgument(0);
                    return jdbcTemplate.update(
                            "update fr_user set gold_balance = ? where id = ?",
                            user.getGoldBalance(),
                            user.getId()
                    ) == 1;
                });
    }

    private NotifyUrlData buildNotifyRequest(String outTradeNo, Integer userId) {
        NotifyUrlData request = new NotifyUrlData();
        request.setOutTradeNo(outTradeNo);
        request.setAttach(String.format("{\"userId\":%d}", userId));
        return request;
    }

    private void insertUser(Integer userId, Integer goldBalance) {
        jdbcTemplate.update("insert into fr_user (id, gold_balance) values (?, ?)", userId, goldBalance);
    }

    private void insertPayOrder(String outTradeNo, Integer userId, Integer productNo, PayOrderStatusEnum status) {
        jdbcTemplate.update(
                "insert into pay_order (creator, create_time, modifyer, modify_time, del_flag, version, user_id, title, out_trade_no, total_fee, status, product_no) values (?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?)",
                0,
                0,
                0,
                0,
                userId,
                "思君币",
                outTradeNo,
                productNo,
                status.name(),
                productNo
        );
    }

    private Integer queryGoldBalance(Integer userId) {
        return jdbcTemplate.queryForObject("select gold_balance from fr_user where id = ?", Integer.class, userId);
    }

    private String queryOrderStatus(String outTradeNo) {
        return jdbcTemplate.queryForObject("select status from pay_order where out_trade_no = ?", String.class, outTradeNo);
    }

    private int countPayOrderByOutTradeNo(String outTradeNo) {
        Integer count = jdbcTemplate.queryForObject("select count(1) from pay_order where out_trade_no = ?", Integer.class, outTradeNo);
        return count == null ? 0 : count;
    }

    private int countPayOrderByStatus(PayOrderStatusEnum status) {
        Integer count = jdbcTemplate.queryForObject("select count(1) from pay_order where status = ?", Integer.class, status.name());
        return count == null ? 0 : count;
    }

    private void createTablesIfNecessary() {
        jdbcTemplate.execute("create table if not exists fr_user (id bigint primary key, gold_balance int)");
        jdbcTemplate.execute("create table if not exists pay_order ("
                + "id bigint auto_increment primary key,"
                + "creator bigint,"
                + "create_time timestamp,"
                + "modifyer bigint,"
                + "modify_time timestamp,"
                + "del_flag tinyint default 0,"
                + "version int default 0,"
                + "user_id int,"
                + "title varchar(255),"
                + "out_trade_no varchar(64),"
                + "total_fee int,"
                + "status varchar(32),"
                + "product_no int"
                + ")");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = PayOrderDao.class)
    @Import({WxPayServiceImpl.class, PayOrderServiceImpl.class})
    static class TestApplication {
    }
}
