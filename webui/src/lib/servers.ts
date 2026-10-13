import type { Server } from './api.ts';
import { ago } from './format.ts';

/** "Synced 5 minutes ago", the sync error, or the running sync's progress. */
export function serverStatus(server: Server): string {
	if (server.progress) return server.progress;
	if (!server.isSignedIn) return 'Signed out';
	if (server.lastSyncError) return server.lastSyncError;
	return server.lastSyncUtcMs ? `Synced ${ago(server.lastSyncUtcMs)}` : 'Not synced yet';
}
