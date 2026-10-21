# operation-cal

`operation-cal` provides date reporting across multiple calendar systems.

## Operations

| Operation | Command | Purpose |
|-----------|---------|---------|
| `CalendarDayOperation` | `today [list|calendar]`, `calendar [list|calendar]`, `tomorrow [list|calendar]` | Reports today's or tomorrow's date in the default calendar or a named supported calendar. `calendar` is another name for `today`. |

## Behavior

The operation reads available calendar systems from `CalendarService`. `today list`, `calendar list` and `tomorrow list` return the supported calendar names.

The operation is addressed and uses operation group `cal`.

## Example Flows

- Show the default current date:
  `today`
- List supported calendars:
  `today list`
- Show today in a specific calendar, with the other name:
  `calendar hebrew`
- Show tomorrow in a specific calendar:
  `tomorrow hebrew`
