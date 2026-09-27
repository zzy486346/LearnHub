-- KEYS[1] stock key, KEYS[2] claimed-user set
-- ARGV[1] user id
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
return 0
