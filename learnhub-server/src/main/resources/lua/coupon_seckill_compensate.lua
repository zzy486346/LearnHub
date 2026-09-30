-- KEYS[1] stock, KEYS[2] claimed-user set, KEYS[3] reservation hash, KEYS[4] compensation marker
-- ARGV[1] user id, ARGV[2] request id
if redis.call('exists', KEYS[4]) == 1 then
    return 2
end
if redis.call('hexists', KEYS[3], ARGV[2]) == 0 then
    return 0
end
redis.call('incr', KEYS[1])
redis.call('srem', KEYS[2], ARGV[1])
redis.call('hdel', KEYS[3], ARGV[2])
redis.call('set', KEYS[4], '1', 'EX', 604800)
return 1
