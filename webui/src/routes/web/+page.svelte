<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Web } from '#lib/api.ts';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import RemoveKey from '#lib/components/RemoveKey.svelte';
	import Row from '#lib/components/Row.svelte';
	import Section from '#lib/components/Section.svelte';
	import WithKey from '#lib/components/WithKey.svelte';
	import { ago } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import Computer from '~icons/material-symbols/computer';
	import Link from '~icons/material-symbols/link';
	import { onMount } from 'svelte';

	let web = $state<Web | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			web = await api.web();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['paired_client', 'profile'], () => void load());
	});

	async function unpair(clientId: number, you: boolean) {
		try {
			await api.unpair(clientId);
			if (you) {
				await session.refresh();
				await goto('/pair');
			} else await load();
		} catch (e) {
			error = problem(e);
		}
	}
</script>

<Page
	title="Web control"
	trail={[{ label: 'Home', href: '/' }]}
	note="Open an address below in a browser and enter the PIN the TV shows. Turn Web control on or off on the TV."
>
	{#if error}<Notice text={error} error />{/if}
	{#if web}
		{#if web.urls.length > 0}
			<Section title="Address" />
			{#each web.urls as url (url)}<Row title={url} icon={Link} />{/each}
		{/if}
		<Section title="Paired browsers" />
		{#each web.clients as client (client.id)}
			<WithKey>
				<Row
					title={client.you ? `${client.name} (this browser)` : client.name}
					summary={[client.profileName, client.address, `seen ${ago(client.lastSeenUtcMs)}`].filter(Boolean).join('  ')}
					icon={Computer}
				/>
				{#snippet key()}<RemoveKey label="Unpair {client.name}" onclick={() => unpair(client.id, client.you)} />{/snippet}
			</WithKey>
		{/each}
		{#if web.clients.length === 0}<Notice text="None yet." />{/if}
	{/if}
</Page>
