# operation-calc

`operation-calc` provides calculator support for interactive protocols and typed operation calls.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `CalculatorOperation` | `calc <expression>` or `CalculatorRequest` | Evaluates algebraic expressions with `CalculatorService`. |

## Behavior

The operation uses the shared command parser for `calc` and passes the expression after the command token to the calculator service.

The operation is addressed and uses operation group `calc`.

`CalculatorService` evaluates with [ParserNG](https://github.com/gbenroscience/ParserNG) in strict mode.
It answers `Invalid expression: <expression>` when:

- the syntax is wrong, such as `2+*3` or `((()`;
- a name is unknown, such as `hello`, `x+1` or `foo(3)`.

An expression can define functions for its own use, separated by `;`:

- `calc f(x)=x+1;f(2)` answers `3.0`.
- `calc sq(x)=x*x;sq(3)+sq(4)` answers `25.0`.

A definition is for that expression only. ParserNG keeps definitions in a store that the whole
server shares, so `CalculatorService` runs one calculation at a time and removes the functions
that a calculation defined when it ends. A later `calc f(2)` is an unknown name. A definition can
replace a built-in function, so `calc sin(x)=99;sin(0)` answers `99.0`, but the next `calc sin(0)`
answers `0.0`.

`ans` is the result of the previous calculation, by any user.

Some results come from ParserNG as they are:

| Expression | Result |
|------------|--------|
| `1/0` | `Infinity` |
| `0/0` | `Infinity` (not `NaN`; ParserNG's behaviour) |
| `sqrt(-1)` | `NaN` |

## Example Flows

- Evaluate a simple expression:
  `calc 2+3`
- Evaluate a grouped expression:
  `calc (42/3.14)*4`
- Define a function and use it in the same expression:
  `calc f(x)=x*x;f(3)+f(4)`
