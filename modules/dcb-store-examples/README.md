# dcb-store-examples

The examples from <https://dcb.events/examples/>, implemented with this library to demonstrate how
`DcbStore`, the projections and the decision models are used. Not published.

## Layout

Each example is one package, with the same structure:

- **main** `dcbstore.examples.<example>`: the events (one case class each), the projections (as
  `SingleProjection`s), and the command handlers. A command handler builds a decision model, checks
  the business rules on its state, and appends the new events with the decision model's append
  condition. It is written against `DcbStore[EventType, DataSuper]` only, so it works with any store.
- **test** `scalatest.dcbstore.examples.<example>`: the given/when/then test cases of the example's
  page, run against the in-memory store, written with `scenario` of the shared base class `ExampleSpec`. Where useful, additional tests show the append condition
  rejecting a concurrent, conflicting decision, using `ConcurrentWriterStore` from the test sources,
  which appends given events between the command handler's read and its append, or between two of its reads.

Shared by all examples, in `ExampleConventions.scala`: `EventType = String`, `DataSuper = AnyRef`,
the event type derived from the data's simple class name, the `event` helper, and for the command
handlers `Rejected` and `appendIfAccepted`. Import them with
`import dcbstore.examples.{*, given}`.

## Examples

| Example                                                                                  | Package                    | Status  |
|------------------------------------------------------------------------------------------|----------------------------|---------|
| [Course subscriptions](https://dcb.events/examples/course-subscriptions/)                | `coursesubscriptions`      | done    |
| [Unique username](https://dcb.events/examples/unique-username/)                          | `uniqueusername`           | done    |
| [Product reviews](https://dcb.events/examples/product-reviews/)                          | `productreviews`           | done    |
| [Bike rental](https://dcb.events/examples/bike-rental/)                                  | `bikerental`               | done    |
| [Library loans](https://dcb.events/examples/library-loans/)                              | `libraryloans`             | done    |
| [Invoice number](https://dcb.events/examples/invoice-number/)                            | `invoicenumber`            | done    |
| [Dynamic product price](https://dcb.events/examples/dynamic-product-price/)              | `dynamicproductprice`      | planned |
| [Event-sourced aggregate](https://dcb.events/examples/event-sourced-aggregate/)          | `eventsourcedaggregate`    | planned |
| [Opt-in token](https://dcb.events/examples/opt-in-token/)                                | `optintoken`               | planned |
| [Prevent record duplication](https://dcb.events/examples/prevent-record-duplication/)    | `preventrecordduplication` | planned |

## Running

```
sbt testExamples
```
