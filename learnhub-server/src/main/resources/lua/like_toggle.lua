-- KEYS[1] target user set, KEYS[2] pending publish hash, KEYS[3] target registry set,
-- KEYS[4] reconciliation lock
-- ARGV[1] user id, ARGV[2] desired state (1/0), ARGV[3] event id,
-- ARGV[4] serialized event, ARGV[5] target field
if redis.call('exists', KEYS[4]) == 1 then
    return -1
end
local member = redis.call('sismember', KEYS[1], ARGV[1])
local desired = tonumber(ARGV[2])
if (desired == 1 and member == 1) or (desired == 0 and member == 0) then
    return 0
end
if desired == 1 then
    redis.call('sadd', KEYS[1], ARGV[1])
else
    redis.call('srem', KEYS[1], ARGV[1])
end
redis.call('hset', KEYS[2], ARGV[3], ARGV[4])
redis.call('sadd', KEYS[3], ARGV[5])
return 1
