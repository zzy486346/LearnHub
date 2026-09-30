-- KEYS[1] stock, KEYS[2] claimed-user set, KEYS[3] reservation hash; all share a hash tag.
-- ARGV[1] expected stock; remaining arguments are triplets: user id, request id, status.
-- Only rebuild a missing key so an online reservation can never be overwritten.
if redis.call('exists', KEYS[1]) == 1 then
    return 0
end
redis.call('set', KEYS[1], ARGV[1])
redis.call('del', KEYS[2])
redis.call('del', KEYS[3])
for i = 2, #ARGV, 3 do
    redis.call('sadd', KEYS[2], ARGV[i])
    if ARGV[i + 2] ~= 'SUCCESS' then
        redis.call('hset', KEYS[3], ARGV[i + 1], ARGV[i] .. '|0|P')
    end
end
return 1
