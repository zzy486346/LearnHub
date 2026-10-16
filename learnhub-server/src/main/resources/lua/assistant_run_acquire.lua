local now = redis.call('TIME')
local now_millis = (tonumber(now[1]) * 1000) + math.floor(tonumber(now[2]) / 1000)
local lease_millis = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now_millis)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now_millis - 60000)

if redis.call('EXISTS', KEYS[1]) == 1 then
    return -1
end
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then
    return -2
end
if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[4]) then
    return -3
end

local acquired = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', lease_millis)
if not acquired then
    return -1
end
redis.call('ZADD', KEYS[2], now_millis + lease_millis, ARGV[1])
local rate_sequence = redis.call('INCR', KEYS[4])
redis.call('ZADD', KEYS[3], now_millis, ARGV[1] .. ':' .. rate_sequence)
redis.call('PEXPIRE', KEYS[3], 120000)
redis.call('PEXPIRE', KEYS[4], 120000)
return 1
