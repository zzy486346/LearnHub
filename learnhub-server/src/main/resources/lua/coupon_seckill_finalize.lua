-- KEYS[1] reservation hash, KEYS[2] compensation marker
-- ARGV[1] request id, ARGV[2] user id
-- Change the short-lived Redis reservation to durable only after the PENDING order commits.
if redis.call('exists', KEYS[2]) == 1 then
    return 0
end
local value = redis.call('hget', KEYS[1], ARGV[1])
if not value then
    return 0
end
local separator = string.find(value, '|')
local user = separator and string.sub(value, 1, separator - 1) or ''
if user ~= ARGV[2] then
    return 0
end
local second = string.find(value, '|', separator + 1)
local timestamp = second and string.sub(value, separator + 1, second - 1) or '0'
redis.call('hset', KEYS[1], ARGV[1], ARGV[2] .. '|' .. timestamp .. '|P')
return 1
