<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, problem, type FoundServer } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import Facts from '#lib/components/Facts.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Readout from '#lib/components/Readout.svelte';
	import StatusLine from '#lib/components/StatusLine.svelte';
	import TextField from '#lib/components/TextField.svelte';
	import Login from '~icons/material-symbols/login';
	import QrCode from '~icons/material-symbols/dialpad';
	import { onDestroy, onMount } from 'svelte';

	/**
	 * Adding a server the way the official Jellyfin apps do: one address (an IP address, a host
	 * name or a full URL), then Quick Connect or a password. The same server added again under
	 * another address updates the one already here.
	 */
	let step = $state<'address' | 'signIn' | 'quickConnect'>('address');
	let address = $state(page.url.searchParams.get('address') ?? '');
	let found = $state<FoundServer | null>(null);
	let username = $state('');
	let password = $state('');
	let code = $state<string | null>(null);
	let busy = $state(false);
	let error = $state<string | null>(null);
	let poll: ReturnType<typeof setInterval> | null = null;

	onMount(() => {
		if (address) void find();
	});

	onDestroy(() => {
		stopPolling();
		if (found && step === 'quickConnect') void api.cancelSignIn(found.signInId).catch(() => {});
	});

	async function run(action: () => Promise<void>) {
		busy = true;
		error = null;
		try {
			await action();
		} catch (e) {
			error = problem(e);
		} finally {
			busy = false;
		}
	}

	function find(event?: SubmitEvent) {
		event?.preventDefault();
		return run(async () => {
			found = await api.findServer(address.trim());
			step = 'signIn';
		});
	}

	function signIn(event: SubmitEvent) {
		event.preventDefault();
		return run(async () => {
			const { id } = await api.signIn(found!.signInId, username, password);
			password = '';
			await goto(`/servers/${id}`);
		});
	}

	function quickConnect() {
		return run(async () => {
			code = (await api.quickConnect(found!.signInId)).code;
			step = 'quickConnect';
			poll = setInterval(check, 3000);
		});
	}

	async function check() {
		if (!found) return;
		try {
			const state = await api.signInState(found.signInId);
			if (state.state === 'signedIn' && state.serverId) {
				stopPolling();
				await goto(`/servers/${state.serverId}`);
			} else if (state.state === 'failed') {
				stopPolling();
				error = state.message ?? 'That didn’t work.';
				step = 'signIn';
			}
		} catch (e) {
			stopPolling();
			error = problem(e);
			step = 'address';
		}
	}

	function stopPolling() {
		if (poll) clearInterval(poll);
		poll = null;
	}

	function back() {
		stopPolling();
		error = null;
		if (step === 'quickConnect') {
			if (found) void api.cancelSignIn(found.signInId).catch(() => {});
			step = 'signIn';
		} else {
			step = 'address';
		}
	}
</script>

<Page
	title="Add server"
	trail={[{ label: 'Home', href: '/' }, { label: 'Servers', href: '/servers' }]}
	note="Type the server's address: an IP address, a host name or a full URL. Then sign in with Quick Connect, approved in another Jellyfin app, or a username and password."
>
	{#snippet overview()}
		{#if found}
			<Readout>
				<StatusLine label="Found" text={found.name} />
				<Facts items={[['Server version', `Jellyfin ${found.version}`], ['Address', found.address]]} />
			</Readout>
		{/if}
	{/snippet}

	{#if error}<Notice text={error} error />{/if}

	{#if step === 'address'}
		<form class="flex max-w-lg flex-col gap-3 px-4 py-2" onsubmit={find}>
			<!-- svelte-ignore a11y_autofocus -->
			<TextField label="Server address" bind:value={address} inputmode="url" placeholder="192.168.1.20" autocomplete="url" autofocus required />
			<Button label="Connect" type="submit" primary disabled={busy || !address.trim()} />
		</form>
	{:else if step === 'signIn' && found}
		{#if found.quickConnect}
			<Buttons><Button label="Use Quick Connect" icon={QrCode} primary onclick={quickConnect} disabled={busy} /></Buttons>
		{/if}
		<form class="flex max-w-lg flex-col gap-3 px-4 py-2" onsubmit={signIn}>
			<TextField label="Username" bind:value={username} autocomplete="username" required />
			<TextField label="Password" type="password" bind:value={password} autocomplete="current-password" />
			<Button label="Sign in" icon={Login} type="submit" primary={!found.quickConnect} disabled={busy || !username.trim()} />
			<Button label="Back" onclick={back} />
		</form>
	{:else if step === 'quickConnect'}
		<div class="flex flex-col gap-2 p-4">
			<p class="field-label text-text-muted">Quick Connect code</p>
			<p class="tabular text-display-m font-bold tracking-[0.12em] text-accent" aria-live="polite">{code}</p>
			<p class="text-body-m text-text-muted">In another Jellyfin app signed in as you, open Quick Connect and enter this code.</p>
		</div>
		<Buttons><Button label="Back" onclick={back} /></Buttons>
	{/if}
</Page>
