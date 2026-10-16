if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
redis.call('DEL', KEYS[3])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
