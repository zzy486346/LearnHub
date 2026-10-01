-- KEYS[1] live delta, KEYS[2] live relations, KEYS[3] pending publish,
-- KEYS[4] live event ids, KEYS[5] reconciliation lock, KEYS[6..n] event dedup keys. Every event contributes
-- five ARGV values: target field, relation field, delta, relation value, event id.
local processed = 0
if redis.call('exists', KEYS[5]) == 1 then
    return -1
end
for i = 6, #KEYS do
    local arg = (i - 6) * 5 + 1
    if redis.call('set', KEYS[i], '1', 'NX', 'EX', 604800) then
        redis.call('hincrby', KEYS[1], ARGV[arg], ARGV[arg + 2])
        local current = redis.call('hget', KEYS[2], ARGV[arg + 1])
        local incomingSequence = tonumber(string.match(ARGV[arg + 3], '^[^|]+|([^|]+)'))
        local currentSequence = current and tonumber(string.match(current, '^[^|]+|([^|]+)')) or -1
        if incomingSequence > currentSequence then
            redis.call('hset', KEYS[2], ARGV[arg + 1], ARGV[arg + 3])
        end
        redis.call('hset', KEYS[4], ARGV[arg + 4], '1')
        processed = processed + 1
    end
    redis.call('hdel', KEYS[3], ARGV[arg + 4])
end
return processed
