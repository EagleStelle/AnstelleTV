<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, problem, type Lineup, type Reach } from '#lib/api.ts';
	import LineupBuilder from '#lib/components/LineupBuilder.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import ReachReadout from '#lib/components/ReachReadout.svelte';
	import { onMount } from 'svelte';

	const id = Number(page.params.id);
	let name = $state('');
	let lineup = $state<Lineup>({ groups: [], includes: [], excludes: [] });
	let loaded = $state(false);
	let reach = $state<Reach | null>(null);
	let error = $state<string | null>(null);

	onMount(async () => {
		try {
			const [detail, current] = await Promise.all([api.channel(id), api.lineup(id)]);
			name = detail.channel.name;
			lineup = current.lineup;
			reach = current.reach;
			loaded = true;
		} catch (e) {
			error = problem(e);
		}
	});

	async function save(current: Lineup): Promise<string | null> {
		try {
			await api.setLineup(id, current);
			await goto(`/channels/${id}`);
			return null;
		} catch (e) {
			return problem(e);
		}
	}
</script>

<Page
	title="Lineup"
	trail={[
		{ label: 'Home', href: '/' },
		{ label: 'Live TV', href: '/channels' },
		{ label: name || 'Channel', href: `/channels/${id}` }
	]}
	note="A series or movie plays when it matches every rule in a set. Changes start after the next program."
>
	{#snippet aside()}<ReachReadout {reach} />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	{#if loaded}
		<LineupBuilder bind:lineup bind:reach saveLabel="Save lineup" onsave={save} />
	{/if}
</Page>
