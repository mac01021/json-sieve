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

## The filter language

Hi
