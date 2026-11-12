-- KEYS: 聚合 Hash、SDS 总计数；ARGV: 槽位数、每槽字节数、字段编号。
-- 所有读取、校验和编码均在写入前完成：Lua 运行错误不会回滚已执行的写命令。
local aggKey = KEYS[1]
local cntKey = KEYS[2]
local schemaLen = tonumber(ARGV[1])
local fieldSize = tonumber(ARGV[2])
local field = ARGV[3]
local idx = tonumber(field)

if not schemaLen or schemaLen < 1 or schemaLen % 1 ~= 0 or fieldSize ~= 4 then
    return redis.error_reply('Invalid counter schema')
end
if not idx or idx % 1 ~= 0 or idx < 0 or idx >= schemaLen or tostring(idx) ~= field then
    return redis.error_reply('Invalid counter field')
end

local rawDelta = redis.call('HGET', aggKey, field)
if not rawDelta then
    return 0
end
local delta = tonumber(rawDelta)
-- Redis Hash 支持 int64，但 Lua number 只能精确表达这个范围内的整数。
if not string.match(rawDelta, '^%-?%d+$') or not delta or math.abs(delta) > 9007199254740991 then
    return redis.error_reply('Invalid counter delta')
end
if delta == 0 then
    redis.call('HDEL', aggKey, field)
    return 0
end

-- 总计数缺失说明快照尚未由位图事实重建：读路径正是以“缺失/长度异常”作为重建触发条件。
-- 这里不能写零值快照占位，否则长度校验会通过、重建被永久跳过，历史计数静默丢失。
-- 保留增量，等读路径重建出快照后由下一轮刷写叠加（重建会清理已折叠的字段）。
local cnt = redis.call('GET', cntKey)
if not cnt then
    return 0
elseif string.len(cnt) ~= schemaLen * fieldSize then
    return redis.error_reply('Invalid counter length')
end

local off = idx * fieldSize
local bytes = {string.byte(cnt, off + 1, off + 4)}
local current = 0
for i = 1, 4 do current = current * 256 + bytes[i] end
-- 与现有读取方式一致，槽位表示无符号 32 位整数；拒绝溢出，不能静默回绕。
if delta > 4294967295 - current then
    return redis.error_reply('Counter overflow')
end
local value = math.max(0, current + delta)
local encoded = {}
for i = 4, 1, -1 do
    encoded[i] = value % 256
    value = math.floor(value / 256)
end
local updated = string.sub(cnt, 1, off) .. string.char(unpack(encoded))
    .. string.sub(cnt, off + fieldSize + 1)

redis.call('SET', cntKey, updated)
-- 执行期间 HINCRBY 无法插入；后到的事件会重新创建字段，留给下一轮。
redis.call('HDEL', aggKey, field)
return delta
