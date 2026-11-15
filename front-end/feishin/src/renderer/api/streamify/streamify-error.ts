/**
 * Error thrown by every `StreamifyController` endpoint stub.
 *
 * The Streamify server type is registered in the UI (see PATCHES.md) before any
 * endpoint is implemented. Until an endpoint is made real, calling it through
 * the API controller raises this error, which carries the endpoint name so the
 * caller (and logs) can identify exactly which surface is still missing.
 */
export class StreamifyNotImplementedError extends Error {
    constructor(endpoint: string) {
        super(`Streamify endpoint '${endpoint}' is not implemented yet`);
        this.name = 'StreamifyNotImplementedError';
    }
}
