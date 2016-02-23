# sifter
A daemon for browsing events from a kafka topic

- Binds to port 5555
- Listens for websocket connections at `/websock`
- Communicates with each client over a websocket via a json-based message set.

## The message set

At any time, the client may send a *command*.  A command is a json object containing
exactly one of the following keys: `fetch`, `tail`, `pause`, `blocks` (this last one is not yet supported).
Behavior in the presence of more than one of these is not defined.  

Within a session, the server will carry out at most one command at a time.  Issuing a command will 
halt the execution of any previously issued command that has not yet terminated.

For each of these command types, additional keys may be required:

- `pause` requires no other keys.  It causes the server to stop carrying out the 
last issued command if it is still in progress.

- `tail` requires the additional key `filter`.  The filter value is a string containing
and expression from the filter language described below.  It causes the server to stream
events from the end of the topic as they arrive in real time.

- `fetch` requires `filter` as well as `instant`.  `instant` is a millisecond-precision unix
timestamp referring to the block of events (log segment) that the client would like to fetch.
The last few events sent may actually be from the following log segment, but each event comes
with its offset number so that the client will be able to figure out what it needs to.

- `block` (coming soon) requires `instant`, and will cause the server to send a list of
the starting offsets of the last 1000 log segments leading up to the given instant.

## Event messages

Every log event sent in response to a `fetch` or `tail` request has the following
structure:

    { "offset": 12345,
      "event": {
            "raw": "log message contents",
            "struct": { ... }

      }}

* The `offset` key corresponds to the offset of the event in Kafka.
* The `event` key contains the contents of the event itself.
    - `raw` is just a string containing whatever message was in Kafka.
    - `struct` will only be present if the message in Kafka was a valid JSON form.
        When this is the case, it is that JSON form, but not embedded in a string.


## The filter language

A test is one of

- a string match, eg `foozles`, `"foozles and woozles"`
- a field check,  eg `pckMsg.stdEnvelope.guid = abcd-1234-gfhij`, `timestamp="2016-01-02T23:55:03.041Z", level=INFO`
- a conjuction of simpler tests

Regexp matches as a test type will be supported in the near future if we actually use this thing.

A conjunction can be any of

- `(or {test1} {test2} {test3} ...)`
- `(and {test1} {test2} ...)`
- `(not {test})`

Field checks only work if the `struct` field of the event is present.
They are applied to the `struct` form, and can be dotted out to arbitray depths.

A filter string consistes of a list of tests, which are wrapped in an implicit `and` for evaluation.
Whitespace matters not.  Here's a contrived example of a filter.

    (and (or host = dp-dev-svc0-1.dev.dp.pvt
             host = dp-dev-svc0-2.dev.dp.pvt
             host = dp-dev-svc0-2.dev.dp.pvt)
        
         app = coordinator-fightmetric
    
         "Oh no!  The processor timed out while it was running queries!")
    
This is a single filter and will only admit events that are from the specified app, running on one of the three specified hosts, and contain the specified string content.

