# operation-weather

`operation-weather` provides current weather lookup.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `WeatherOperation` | `weather <location>` or `WeatherRequest` | Looks up weather for a location and returns Celsius/Fahrenheit temperature plus description. |

## Behavior

The operation delegates geocoding and weather retrieval to `WeatherService`. It returns `null` when no weather result can be obtained, allowing the operation chain to continue.

The operation is addressed and uses operation group `weather`.

## Example Flows

- Ask for a city's weather:
  `weather Boston`
- Ask for a more specific place:
  `weather San Francisco, CA`
