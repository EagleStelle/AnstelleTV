<script lang="ts">
	import { api, problem, type Server } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { serverStatus } from '#lib/servers.ts';
	import Add from '~icons/material-symbols/add';
	import CloudOff from '~icons/material-symbols/cloud-off-outline';
	import Dns from '~icons/material-symbols/dns-outline';
	import Sync from '~icons/material-symbols/sync';
	import { onMount } from 'svelte';

	let servers = $state<Server[] | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			servers = await api.servers();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['media_server', 'sync'], () => void load());
	});

	/** "alice, synced 5 minutes ago"; a problem or a running sync speaks alone. */
	function summary(server: Server): string {
		const status = serverStatus(server);
		const trouble = !server.isSignedIn || !!server.lastSyncError;
		if (server.userName && !trouble && !server.progress) return `${server.userName}, ${status.charAt(0).toLowerCase()}${status.slice(1)}`;
		return status;
	}
</script>

<Page
	title="Servers"
	trail={[{ label: 'Home', href: '/' }]}
	note="AnstelleTV builds its channels from the Jellyfin libraries of the servers you connect here. Add one with its address, then sign in with Quick Connect or a password."
>
	{#snippet actions()}<Button label="Add server" icon={Add} primary href="/servers/add" />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	{#each servers ?? [] as server (server.id)}
		<LinkRow
			title={server.name}
			summary={summary(server)}
			icon={server.progress ? Sync : !server.isSignedIn || server.lastSyncError ? CloudOff : Dns}
			href="/servers/{server.id}"
		/>
	{/each}
	{#if servers?.length === 0}<Notice text="No servers yet." />{/if}
</Page>
