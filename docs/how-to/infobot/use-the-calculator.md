# Use the Calculator

`calc` evaluates an arithmetic expression and replies with the result. Address the bot as you do
for other commands, for example `!calc 6*7` on IRC.

```text
calc 6*7
```

The bot replies `The result of 6*7 is: 42.0`.

## Write an Expression

You can use these operators, functions and constants:

| Kind | Examples |
|------|----------|
| Operators | `+`, `-`, `*`, `/`, `^` (power), `%` (remainder), `!` (factorial), parentheses |
| Functions | `sqrt(2)`, `abs(-3)`, `log(100)` (base 10), `ln(e)`, `sin(pi/2)` |
| Constants | `pi`, `e` |

Trigonometric functions use radians: `sin(pi/2)` is `1.0`, and `sin(90)` is not.

## Use the Previous Result

`ans` is the result of the previous calculation. After `calc 6*7`, `calc ans+1` gives `43.0`.

`ans` is shared: it is the previous calculation by any user, on any channel. If someone else
calculates between your two commands, `ans` is their result.

## Define a Function

Define a function, then use it in the same expression. Separate the parts with `;`:

```text
calc f(x)=x+1;f(2)
calc sq(x)=x*x;sq(3)+sq(4)
```

These give `3.0` and `25.0`.

A definition is for that expression only. A later `calc f(2)` is an error, and another user's
expressions never see your definitions. A definition can replace a built-in function for that
expression: `calc sin(x)=99;sin(0)` gives `99.0`, and the next `calc sin(0)` gives `0.0`.

## When the Bot Says "Invalid expression"

The bot replies `Invalid expression: <expression>` when:

- the syntax is wrong, such as `2+*3` or `((()`;
- a name is unknown, such as `hello`, `x+1` or `foo(3)`.

A name that is not a constant, a built-in function or a function you defined in the same
expression is an error. It is not taken as zero.

## Special Results

| Expression | Result |
|------------|--------|
| `1/0` | `Infinity` |
| `0/0` | `NaN` |
| `sqrt(-1)` | `NaN` |

Results are floating-point numbers, so the last digit can be wrong: `1E-5` gives
`9.999999999999999E-6`.
