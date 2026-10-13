import { api, ApiError, type Health, type Session } from './api.ts';

export type SessionStatus = 'loading' | 'paired' | 'unpaired' | 'offline';

/** Whether this browser is paired with the TV, who it speaks for, and what the TV is. */
class SessionStore {
	status = $state<SessionStatus>('loading');
	session = $state<Session | null>(null);
	health = $state<Health | null>(null);

	/** Only the owner restores a backup. */
	isOwner = $derived(this.session?.profile.role === 'owner');

	async refresh(): Promise<void> {
		try {
			this.health = await api.health();
		} catch {
			this.status = 'offline';
			return;
		}
		try {
			this.session = await api.session();
			this.status = 'paired';
		} catch (error) {
			this.session = null;
			this.status = error instanceof ApiError && error.status === 401 ? 'unpaired' : 'offline';
		}
	}

	async logout(): Promise<void> {
		try {
			await api.logout();
		} finally {
			this.session = null;
			this.status = 'unpaired';
		}
	}
}

export const session = new SessionStore();
