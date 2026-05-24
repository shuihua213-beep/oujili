package com.wxmblog.nostalgia.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wxmblog.nostalgia.common.enums.user.PayOrderStatusEnum;
import com.wxmblog.nostalgia.dao.PayOrderDao;
import com.wxmblog.nostalgia.entity.PayOrderEntity;
import com.wxmblog.nostalgia.service.PayOrderService;
import org.springframework.stereotype.Service;


@Service("payOrderService")
public class PayOrderServiceImpl extends ServiceImpl<PayOrderDao, PayOrderEntity> implements PayOrderService {

    @Override
    public boolean markSuccessIfPrePay(String outTradeNo) {
        LambdaUpdateWrapper<PayOrderEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PayOrderEntity::getOutTradeNo, outTradeNo)
                .eq(PayOrderEntity::getStatus, PayOrderStatusEnum.PRE_PAY)
                .set(PayOrderEntity::getStatus, PayOrderStatusEnum.SUCCESS);
        return this.update(updateWrapper);
    }
}
