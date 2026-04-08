# operation-calc

`operation-calc` provides calculator support for interactive protocols and typed operation calls.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `CalculatorOperation` | `calc <expression>` or `CalculatorRequest` | Evaluates algebraic expressions with `CalculatorService`. |

## Behavior

The operation uses the shared command parser for `calc` and passes the expression after the command token to the calculator service.

The operation is addressed and uses operation group `calc`.
