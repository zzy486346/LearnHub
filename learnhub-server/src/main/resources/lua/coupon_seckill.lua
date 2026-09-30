-- KEYS[1] stock key, KEYS[2] claimed-user set, KEYS[3] reservation hash
-- ARGV[1] user id, ARGV[2] request id, ARGV[3] reservation epoch millis
local stock = tonumber(redis.call('get', KEYS[1]) or '-1')
if stock < 0 then
    return 3
end
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    return 2
end
if stock <= 0 then
    return 1
end
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[1])
redis.call('hset', KEYS[3], ARGV[2], ARGV[1] .. '|' .. ARGV[3] .. '|R')
return 0
