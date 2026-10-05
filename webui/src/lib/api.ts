// Typed client for the REST API served by the TV under /api.
// Authentication is an HttpOnly cookie set during pairing, so requests only need
// `credentials: 'same-origin'`.

export interface Health {
	app: string;
	version: string;
	deviceName: string;
	isTv: boolean;
}

export interface Session {
	clientId: number;
	clientName: string;
	deviceName: string;
	version: string;
}

export interface PairStart {
	requestId: string;
	expiresInSeconds: number;
	deviceName: string;
}

export interface Profile {
	id: number;
	name: string;
	isKid: boolean;
	hasPin: boolean;
}

export interface Channel {
	id: number;
	number: number;
	name: string;
	logoUri?: string;
	isHidden: boolean;
	isFavorite: boolean;
	groupId?: number;
}

export class ApiError extends Error {
	constructor(
		readonly status: number,
		readonly code: string,
		message: string
	) {
		super(message);
	}
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
	const response = await fetch(`/api${path}`, {
		method,
		credentials: 'same-origin',
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	if (!response.ok) {
		let code = `http_${response.status}`;
		let message = response.statusText;
		try {
			const error = (await response.json()) as { error: string; message: string };
			code = error.error;
			message = error.message;
		} catch {
			// Not a JSON error body; keep the HTTP status text.
		}
		throw new ApiError(response.status, code, message);
	}
	if (response.status === 204) return undefined as T;
	return (await response.json()) as T;
}

export const api = {
	health: () => request<Health>('GET', '/health'),
	session: () => request<Session>('GET', '/session'),
	logout: () => request<void>('DELETE', '/session'),
	startPairing: (clientName: string) => request<PairStart>('POST', '/pair/start', { clientName }),
	confirmPairing: (requestId: string, pin: string) =>
		request<{ clientId: number; clientName: string }>('POST', '/pair/confirm', { requestId, pin }),
	profiles: () => request<Profile[]>('GET', '/profiles'),
	channels: () => request<Channel[]>('GET', '/channels')
};
