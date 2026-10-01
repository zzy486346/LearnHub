-- KEYS[1] batch delta, KEYS[2] batch relations, KEYS[3] batch events,
-- KEYS[4] processing registry
-- ARGV[1] batch id
redis.call('del', KEYS[1], KEYS[2], KEYS[3])
redis.call('srem', KEYS[4], ARGV[1])
return 1
