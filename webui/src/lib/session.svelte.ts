import { api, ApiError, type Health, type Session } from './api';

export type SessionStatus = 'loading' | 'paired' | 'unpaired' | 'offline';

/** Whether this browser is paired with the TV, and what the TV is. */
class SessionStore {
	status = $state<SessionStatus>('loading');
	session = $state<Session | null>(null);
	health = $state<Health | null>(null);

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
