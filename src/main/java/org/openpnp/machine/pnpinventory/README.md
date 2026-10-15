# PnpInventory vision feeder

Select `PnpInventoryFeederAutoVision` when adding a feeder. This package contains
independent copies of the Bamboo auto-vision feeder, its vision base class, and
its configuration wizard. Common OpenPnP vision utilities and pipeline resources
remain shared.

Enable **Feed after pick** in **PnpInventory Feed Timing** to pick from already
loaded tape before advancing it. Vision follows the configured calibration
trigger and detects sprocket holes to calculate the part position, as in Bamboo.
The job processor picks and retracts the nozzle, then the feeder advances the tape
when all parts in the current feed cycle have been picked. Part pitch larger than
feed pitch causes multiple actuations. The optional Post Pick actuator runs last.
With the checkbox off, the original feed-before-pick sequence is used.

**Feed delay (ms)** defaults to zero. It waits after each feed actuation, including
between multiple advances, before further vision or the Post Pick actuator. Set
it to the time your feeder needs to complete its movement. This is a fixed wait,
not a hardware ready-signal timeout. Both settings are saved in machine.xml.

Start reverse mode with tape already positioned for a complete pick cycle and
reset the feed count when loading tape. Automatic part return is disabled in
reverse mode because tape may already have advanced after picking. If a deferred
advance fails, retries stop to avoid advancing an uncertain tape position. Check
and restore the tape position and feed count, then apply Feed after pick off and
apply it on again to clear the fault.

## PnPInventory REST actuator and slot feeder

Implemented against `doc/System_Architecture_and_Interfaces.pdf`, version 0.9
Draft, including its OpenPnP integration chapter. Creation uses POST; PUT is replacement-only. The integration uses the public backend
API; it does not communicate directly with the F-Bus or database service.

### Setup

1. Add a **PnpInventoryActuator** under the machine's Actuators. Enter the server
   address and port (defaults: `127.0.0.1`, `8080`). The base path is `/api/v1`.
   For TLS, use `https://hostname` in Address. Enter your username/password and
   click **Log in** to obtain the token, or enter a bearer token manually.
   The token is saved in your local `machine.xml`; the password is not saved.
   Login again when the session expires. Browser login is not shared with OpenPnP.
2. For multiple F-Buses, set the actuator's **Bus UUID**. A slot is the current
   **F-Bus address, 0–254**, not an index in the feeder list. The actuator resolves
   that address to the backend's stable feeder UUID. Ambiguous addresses fail.
3. Create the parts and packages in OpenPnP. Set **Machine name** or **Machine UUID** to use the
   backend's `part` and `package` mappings (`sourceId` = OpenPnP ID). Without a
   mapping, the existing OpenPnP ID must exactly equal the backend UUID. Names
   are not used as identity and nothing is automatically created or imported.
4. Add a **PnpInventorySlotFeeder**, select the shared API actuator and enter its
   slot. Click **Sync component**. The feeder reads the confirmed assignment,
   its active container and part, validates the local part/package relationship,
   and then updates the component and pitch. Missing or mismatched local parts
   leave the feeder unchanged. Re-sync resets the feed count and calibration.
5. Configure the pick location, sprocket holes and vision as for the original
   feeder. Enable the feeder after checking these settings.
6. Click **+** to create the next slot. The new feeder receives a separate copy
   of the vision pipeline, tape orientation and timing. Its pick/hole positions
   are translated by the spacing inferred from another slot in the row. For the
   first neighbor, **Initial row spacing (mm)** defaults to 16 mm perpendicular
   to the hole direction (negative Y if holes are not configured). These are
   approximate coordinates: check them before enabling. The new feeder starts
   disabled without a component; sync it separately.

The slot feeder has no Post Pick actuator. Feed and part pitch come from the
backend assignment and cannot be edited here. Each REST feed presents **one
component**, regardless of the mechanical increments required by hardware. The
backend handles those increments; sending a separate API feed per increment
would incorrectly debit stock several times.

### Feed order and completion

Normal mode waits for a successful backend feed, then applies the configured
extra delay and vision policy before OpenPnP picks. **Feed after pick** uses the
already loaded component, then performs the API feed after nozzle retraction.
The extra delay is in addition to backend completion polling. As with the
original reverse mode, prepare the initial loaded pocket yourself and verify
that the backend's consume-on-feed accounting matches your prefetched tape.
Automatic part returns are disabled.

Use the standard Feeders-tab **Feed** and **Pick** actions for manual tests.
They use the selected feeder's slot and require no job. Feed advances once even
in reverse mode; Pick follows the selected normal/reverse order. These actions
move real hardware and update stock. Feed completion timeout is configured per
slot feeder, not on the actuator; its default is 30000 ms.

### Requests, timeouts and recovery

The adapter writes an immutable UUID and request to
`<OpenPnP configuration>/pnpinventory-operations/` before sending it. It polls
`GET /operations/{id}` and proceeds only for `succeeded`. Retry after a lost
response or timeout reuses the same UUID, container, assignment revision and
job context. Pending requests survive application restarts. Do not delete these
files to retry an uncertain movement.

For feed-before-pick, retry the original feed. If its vision fails after the
backend succeeds, the retry reuses the successful operation before trying vision
again. For a pending feed-after-pick operation, **Resume pending feed** finishes
that saved operation without picking again or creating a fresh movement. It
cannot initiate a feed just because a pick has been prepared.

`failed`, `cancelled`, `uncertain` and `reconciled` never count as successful
feeds. Resolve uncertainty in the backend and check the tape position. Then
**Clear resolved feed** releases the local record only when the backend reports
`failed`, `cancelled` or `reconciled`; it refuses an active/uncertain operation.
This is an explicit recovery action before a new feed. A pending request remains
bound to its original endpoint and slot.

### Job context and integration boundary

The executing job processor supplies its actual Job object. The adapter derives
the backend name from its saved filename, stripping `.job.xml` or `.xml`, and
matches exactly one running job for the configured machine. There is no manual
job field in the actuator. An unsaved or unmatched job stops before a new feed.
The backend machine can be specified by its exact name or UUID. If both are
provided they must agree. Part/package mapping uses the resolved UUID too.

Each new OpenPnP run clears the cached job selection. New job feeds validate the
matched job and read its allocation revision automatically. Replayed operations
keep the original operation/cycle and allocation revision. A new physical attempt
at the same placement uses a new operation, so retry/rework stock is counted
separately. The revised backend event contract accepts multiple attempts per
target and deduplicates by attempt/event identity, not placement position alone.

Manual Feed/Pick actions use the manual contract independently of the loaded job.
The backend must implement revision 0.9 POST creation and machine-name lookup;
there is no automatic fallback to the older PUT-create protocol.

This change implements feeding and component synchronization, not the separate
job-planning, import, lifecycle, placement/discard reporting or inventory-return
APIs. Your backend/UI or existing integration must create/start the job and
report its outcomes as specified in sections 7.16–7.17. Manual feeder tests omit
job fields. The backend remains responsible for stock, assignment confirmation,
physical completion and operation reconciliation.

### Validation

`mvn -Dtest='PnpInventory*Test' package` runs feeder-order tests and a local HTTP
server integration suite covering pagination, authentication, immutable replay,
completion/error states, part/package mapping, persistence and row creation.
No live backend or physical machine is required by these tests.
