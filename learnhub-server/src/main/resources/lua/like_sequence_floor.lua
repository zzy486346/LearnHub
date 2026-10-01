local current = tonumber(redis.call('get', KEYS[1]) or '0')
local floor = tonumber(ARGV[1])
if floor > current then
    redis.call('set', KEYS[1], tostring(floor))
    return floor
end
return current
