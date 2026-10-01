local current = tonumber(redis.call('get', KEYS[1]) or '0')
local now = redis.call('time')
local candidate = tonumber(now[1]) * 1000000 + tonumber(now[2])
if candidate <= current then
    candidate = current + 1
end
redis.call('set', KEYS[1], tostring(candidate))
return candidate
