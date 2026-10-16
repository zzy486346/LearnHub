local user_calls = tonumber(redis.call('GET', KEYS[1]) or '0')
local user_tokens = tonumber(redis.call('GET', KEYS[2]) or '0')
local global_calls = tonumber(redis.call('GET', KEYS[3]) or '0')
local global_tokens = tonumber(redis.call('GET', KEYS[4]) or '0')

if user_calls + 1 > tonumber(ARGV[1]) or global_calls + 1 > tonumber(ARGV[1]) then
    return -1
end
if user_tokens + tonumber(ARGV[3]) > tonumber(ARGV[2])
        or global_tokens + tonumber(ARGV[3]) > tonumber(ARGV[2]) then
    return -2
end

redis.call('INCR', KEYS[1])
redis.call('INCRBY', KEYS[2], ARGV[3])
redis.call('INCR', KEYS[3])
redis.call('INCRBY', KEYS[4], ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[4])
redis.call('EXPIRE', KEYS[2], ARGV[4])
redis.call('EXPIRE', KEYS[3], ARGV[4])
redis.call('EXPIRE', KEYS[4], ARGV[4])
return 1
