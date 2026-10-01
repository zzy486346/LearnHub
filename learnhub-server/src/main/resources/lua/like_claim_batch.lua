-- KEYS[1] live delta, KEYS[2] live relations, KEYS[3] live events,
-- KEYS[4] batch delta, KEYS[5] batch relations, KEYS[6] batch events,
-- KEYS[7] processing registry, KEYS[8] reconciliation lock; ARGV[1] batch id.
if redis.call('exists', KEYS[8]) == 1 then
    return -1
end
if redis.call('scard', KEYS[7]) > 0 then
    return 0
end
if redis.call('exists', KEYS[1]) == 0 and redis.call('exists', KEYS[2]) == 0 and redis.call('exists', KEYS[3]) == 0 then
    return 0
end
if redis.call('exists', KEYS[1]) == 1 then
    redis.call('rename', KEYS[1], KEYS[4])
end
if redis.call('exists', KEYS[2]) == 1 then
    redis.call('rename', KEYS[2], KEYS[5])
end
if redis.call('exists', KEYS[3]) == 1 then
    redis.call('rename', KEYS[3], KEYS[6])
end
redis.call('sadd', KEYS[7], ARGV[1])
return 1
