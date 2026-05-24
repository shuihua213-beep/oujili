-- ============================================
-- 微信支付相关表优化脚本
-- ============================================

-- 1. 为pay_order表添加唯一索引和普通索引
-- 目的：提高查询性能，确保out_trade_no唯一性

-- 检查索引是否存在，如果不存在则创建
SET @dbname = DATABASE();
SET @tablename = 'pay_order';
SET @indexname = 'uk_out_trade_no';

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE table_schema = @dbname
    AND table_name = @tablename
    AND index_name = @indexname
  ) > 0,
  'SELECT ''Index uk_out_trade_no already exists'' AS message',
  CONCAT('CREATE UNIQUE INDEX ', @indexname, ' ON ', @tablename, '(out_trade_no)')
));
PREPARE statement FROM @preparedStatement;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 添加user_id和status的复合索引
SET @indexname = 'idx_user_status';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE table_schema = @dbname
    AND table_name = @tablename
    AND index_name = @indexname
  ) > 0,
  'SELECT ''Index idx_user_status already exists'' AS message',
  CONCAT('CREATE INDEX ', @indexname, ' ON ', @tablename, '(user_id, status)')
));
PREPARE statement FROM @preparedStatement;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 添加create_time索引，便于查询历史订单
SET @indexname = 'idx_create_time';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE table_schema = @dbname
    AND table_name = @tablename
    AND index_name = @indexname
  ) > 0,
  'SELECT ''Index idx_create_time already exists'' AS message',
  CONCAT('CREATE INDEX ', @indexname, ' ON ', @tablename, '(create_time)')
));
PREPARE statement FROM @preparedStatement;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 2. 为fr_user表添加金币余额相关的索引（可选）
SET @tablename = 'fr_user';
SET @indexname = 'idx_gold_balance';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE table_schema = @dbname
    AND table_name = @tablename
    AND index_name = @indexname
  ) > 0,
  'SELECT ''Index idx_gold_balance already exists'' AS message',
  CONCAT('CREATE INDEX ', @indexname, ' ON ', @tablename, '(gold_balance)')
));
PREPARE statement FROM @preparedStatement;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 3. （可选）创建支付流水记录表，用于更详细的支付追踪
-- 如果需要，可以创建此表
SET @tablename = 'pay_transaction_log';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
    WHERE table_schema = @dbname
    AND table_name = @tablename
  ) > 0,
  'SELECT ''Table pay_transaction_log already exists'' AS message',
  CONCAT('CREATE TABLE ', @tablename, ' (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT ''主键ID'',
    out_trade_no VARCHAR(64) NOT NULL COMMENT ''商户订单号'',
    transaction_id VARCHAR(64) DEFAULT NULL COMMENT ''微信支付订单号'',
    user_id INT NOT NULL COMMENT ''用户ID'',
    amount INT NOT NULL COMMENT ''金币数量'',
    total_fee INT NOT NULL COMMENT ''支付金额（分）'',
    status VARCHAR(32) NOT NULL COMMENT ''状态'',
    request_data TEXT COMMENT ''请求数据'',
    response_data TEXT COMMENT ''响应数据'',
    error_msg VARCHAR(512) DEFAULT NULL COMMENT ''错误信息'',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'',
    INDEX idx_out_trade_no (out_trade_no),
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''支付流水记录表''')
));
PREPARE statement FROM @preparedStatement;
EXECUTE statement;
DEALLOCATE PREPARE statement;

-- 4. 插入测试配置数据（用于测试）
-- 注意：在生产环境中请删除或修改此部分
SET @config_code = 'payMenuList';
SET @config_value = '[{"price":100,"amount":100},{"price":200,"amount":200},{"price":500,"amount":500}]';

-- 这里需要根据实际的配置表结构来调整
-- 假设配置表名为ms_config，字段为code和value
-- 如果表结构不同，请相应调整

-- SELECT 'Database optimization completed!' AS message;
