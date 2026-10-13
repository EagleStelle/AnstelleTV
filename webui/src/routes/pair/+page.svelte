<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, ApiError, problem } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Mark from '#lib/components/Mark.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import TextField from '#lib/components/TextField.svelte';
	import { session } from '#lib/session.svelte.ts';

	let clientName = $state(defaultClientName());
	let requestId = $state<string | null>(null);
	let deviceName = $state('the TV');
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
			error = problem(e);
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
			await goto('/');
		} catch (e) {
			error = problem(e);
			if (e instanceof ApiError && e.code !== 'wrong_pin') requestId = null;
		} finally {
			busy = false;
		}
	}
</script>

<main class="mx-auto flex w-[min(100%,440px)] flex-col gap-4 px-4 py-16">
	<div class="mb-4 flex items-center gap-2.5">
		<Mark size={40} />
		<span class="font-display text-headline-s font-semibold">Anstelle<b class="font-semibold text-accent">TV</b></span>
	</div>
	{#if requestId === null}
		<h1 class="font-display text-headline-m font-semibold">Pair this browser</h1>
		<p class="text-body-m text-text-muted">
			The TV shows a 6-digit PIN while the owner’s or an admin’s profile is on. Pairing lets this browser manage the TV’s servers,
			channels, themes and backups.
		</p>
		<form class="mt-2 flex flex-col gap-3" onsubmit={start}>
			<TextField label="Name for this browser" bind:value={clientName} maxlength={64} required />
			<Button label="Show PIN on TV" type="submit" primary disabled={busy || !clientName.trim()} />
		</form>
	{:else}
		<h1 class="font-display text-headline-m font-semibold">Enter the PIN</h1>
		<p class="text-body-m text-text-muted">Type the PIN {deviceName} shows. It expires in 2 minutes.</p>
		<form class="mt-2 flex flex-col gap-3" onsubmit={confirm}>
			<!-- svelte-ignore a11y_autofocus -->
			<TextField
				label="PIN"
				bind:value={pin}
				tabular
				class="min-h-16 text-center text-headline-l tracking-[0.4em]"
				inputmode="numeric"
				autocomplete="one-time-code"
				pattern={'[0-9]{6}'}
				maxlength={6}
				autofocus
				required
			/>
			<Button label="Pair" type="submit" primary disabled={busy || pin.length !== 6} />
			<Button label="Start over" onclick={() => (requestId = null)} />
		</form>
	{/if}
	{#if error}<div class="-mx-4"><Notice text={error} error /></div>{/if}
</main>
