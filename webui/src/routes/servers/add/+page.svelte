<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, problem, type FoundServer } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import CodeBlocks from '#lib/components/CodeBlocks.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Section from '#lib/components/Section.svelte';
	import TextField from '#lib/components/TextField.svelte';
	import Close from '~icons/material-symbols/close';
	import Link from '~icons/material-symbols/link';
	import Login from '~icons/material-symbols/login';
	import QrCode from '~icons/material-symbols/qr-code-2';
	import { onDestroy, onMount } from 'svelte';

	/**
	 * Adding a server the way the official Jellyfin apps do: one address (an IP address, a host
	 * name or a full URL), then Quick Connect or a password. The same server added again under
	 * another address updates the one already here. Each step is a page of its own, as in the app.
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

	const trail = [
		{ label: 'Home', href: '/' },
		{ label: 'Servers', href: '/servers' }
	];

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
		code = null;
		step = 'quickConnect';
		return run(async () => {
			code = (await api.quickConnect(found!.signInId)).code;
			poll = setInterval(check, 3000);
		}).then(() => {
			if (error) step = 'signIn';
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

{#if step === 'address' || !found}
	<Page title="Add server" {trail} note="Your Jellyfin server's address.">
		<form class="flex max-w-lg flex-col gap-3 px-4 py-2" onsubmit={find}>
			<!-- svelte-ignore a11y_autofocus -->
			<TextField
				label="Server address"
				bind:value={address}
				inputmode="url"
				placeholder="192.168.1.20:8096 or jellyfin.example.com"
				autocomplete="url"
				autofocus
				required
			/>
		</form>
		{#if error}<Notice text={error} error />{/if}
		<Buttons><Button label={busy ? 'Connecting…' : 'Connect'} icon={Link} primary disabled={busy || !address.trim()} onclick={() => find()} /></Buttons>
	</Page>
{:else if step === 'signIn'}
	<Page title="Sign in" {trail} note="{found.name}: Jellyfin {found.version} at {found.address}">
		{#if found.quickConnect}
			<Section title="Quick Connect" />
			<Buttons><Button label="Use Quick Connect" icon={QrCode} primary onclick={quickConnect} disabled={busy} /></Buttons>
			<Section title="Password" />
		{/if}
		<form id="password" class="flex max-w-lg flex-col gap-2 px-4 py-1" onsubmit={signIn}>
			<TextField label="Username" bind:value={username} autocomplete="username" required />
			<TextField label="Password" type="password" bind:value={password} autocomplete="current-password" />
		</form>
		{#if error}<Notice text={error} error />{/if}
		<Buttons>
			<Button label="Back" onclick={back} />
			<Button label={busy ? 'Signing in…' : 'Sign in'} icon={Login} type="submit" form="password" primary={!found.quickConnect} disabled={busy || !username.trim()} />
		</Buttons>
	</Page>
{:else}
	<Page title="Quick Connect" {trail} note="In Jellyfin, open Quick Connect and enter this code.">
		<div class="px-4 pt-4"><CodeBlocks {code} /></div>
		<Notice text={code ? 'Waiting for approval…' : 'Getting a code…'} />
		<Buttons><Button label="Cancel" icon={Close} onclick={back} /></Buttons>
	</Page>
{/if}
