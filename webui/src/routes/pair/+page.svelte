<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, ApiError } from '#lib/api.ts';
	import Brand from '#lib/components/Brand.svelte';
	import { session } from '#lib/session.svelte.ts';
	import Dialpad from '~icons/material-symbols/dialpad';

	let clientName = $state(defaultClientName());
	let requestId = $state<string | null>(null);
	let deviceName = $state('your TV');
	let pin = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);

	function defaultClientName(): string {
		const ua = navigator.userAgent;
		const browser = /Edg\//.test(ua)
			? 'Edge'
			: /Firefox\//.test(ua)
				? 'Firefox'
				: /Chrome\//.test(ua)
					? 'Chrome'
					: /Safari\//.test(ua)
						? 'Safari'
						: 'Browser';
		const os = /Android/.test(ua)
			? 'Android'
			: /iPhone|iPad/.test(ua)
				? 'iOS'
				: /Windows/.test(ua)
					? 'Windows'
					: /Mac OS/.test(ua)
						? 'macOS'
						: /Linux/.test(ua)
							? 'Linux'
							: '';
		return os ? `${browser} on ${os}` : browser;
	}

	function message(e: unknown): string {
		return e instanceof ApiError ? e.message : 'Could not reach the TV.';
	}

	async function start(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		error = null;
		try {
			const result = await api.startPairing(clientName.trim());
			requestId = result.requestId;
			deviceName = result.deviceName;
			pin = '';
		} catch (e) {
			error = message(e);
		} finally {
			busy = false;
		}
	}

	async function confirm(event: SubmitEvent) {
		event.preventDefault();
		if (!requestId) return;
		busy = true;
		error = null;
		try {
			await api.confirmPairing(requestId, pin);
			await session.refresh();
			await goto('/watch');
		} catch (e) {
			error = message(e);
			if (e instanceof ApiError && e.code !== 'wrong_pin') requestId = null;
		} finally {
			busy = false;
		}
	}
</script>

<div class="page">
	<div class="card panel stack">
		<Brand size={36} />
		{#if requestId === null}
			<h1>Pair this browser</h1>
			<p class="muted">
				Your TV will show a 6-digit PIN. Pairing lets this browser manage channels and watch on your
				home network.
			</p>
			<form class="stack" onsubmit={start}>
				<label class="stack field">
					<span>Name for this browser</span>
					<input type="text" bind:value={clientName} maxlength="64" required />
				</label>
				<button class="btn primary" disabled={busy || !clientName.trim()}>Show PIN on TV</button>
			</form>
		{:else}
			<h1>Enter the PIN</h1>
			<p class="muted">Type the PIN shown on {deviceName}. It expires in 2 minutes.</p>
			<form class="stack" onsubmit={confirm}>
				<label class="pin-field">
					<Dialpad class="icon" />
					<input
						type="text"
						inputmode="numeric"
						autocomplete="one-time-code"
						pattern="[0-9]{6}"
						maxlength="6"
						placeholder="000000"
						bind:value={pin}
						required
					/>
				</label>
				<button class="btn primary" disabled={busy || pin.length !== 6}>Pair</button>
				<button type="button" class="btn" onclick={() => (requestId = null)}>Start over</button>
			</form>
		{/if}
		{#if error}
			<p class="error" role="alert">{error}</p>
		{/if}
	</div>
</div>

<style>
	.page {
		min-height: 100vh;
		display: grid;
		place-items: center;
		padding: 1rem;
	}

	.panel {
		width: min(100%, 26rem);
		gap: 1.25rem;
	}

	.field {
		gap: 0.4rem;
	}

	.pin-field {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		color: var(--muted);
	}

	.pin-field input {
		font-family: var(--mono);
		font-size: 1.8rem;
		letter-spacing: 0.4em;
		text-align: center;
		min-height: 3.5rem;
		color: var(--text);
	}

	.error {
		color: var(--danger);
	}
</style>
