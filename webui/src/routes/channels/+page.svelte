<script lang="ts">
	import { api, problem, type Channel } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Search from '#lib/components/Search.svelte';
	import Section from '#lib/components/Section.svelte';
	import { section } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import Add from '~icons/material-symbols/add';
	import Folder from '~icons/material-symbols/folder-outline';
	import ViewCarousel from '~icons/material-symbols/view-carousel-outline';
	import { onMount } from 'svelte';

	let channels = $state<Channel[] | null>(null);
	let bannerSize = $state(10);
	let bannerSizes = $state<number[]>([]);
	let query = $state('');
	let error = $state<string | null>(null);

	async function load() {
		try {
			const result = await api.channels();
			channels = result.channels;
			bannerSize = result.bannerSize;
			bannerSizes = result.bannerSizes;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['channel', 'channel_group', 'channel_season', 'setting'], () => void load());
	});

	async function setBanner(size: number) {
		try {
			await api.setBannerSize(size);
			bannerSize = size;
		} catch (e) {
			error = problem(e);
		}
	}

	const needle = $derived(query.trim().toLowerCase());
	const found = $derived((channels ?? []).filter((c) => c.name.toLowerCase().includes(needle) || String(c.number).startsWith(needle)));
	const custom = $derived((channels ?? []).filter((c) => !c.fromLibrary));
	const library = $derived((channels ?? []).filter((c) => c.fromLibrary));

	function summary(channel: Channel): string {
		return [
			channel.groupName,
			channel.isFavorite ? 'favorite' : null,
			channel.isHidden ? 'hidden' : null,
			channel.seasonLabel ? (channel.inSeason ? `on air ${channel.seasonLabel}` : `off season, on air ${channel.seasonLabel}`) : null
		]
			.filter(Boolean)
			.join(', ');
	}
</script>

{#snippet rows(list: Channel[])}
	{#each list as channel (channel.id)}
		<LinkRow title={channel.name} summary={summary(channel)} href="/channels/{channel.id}">
			{#snippet leading()}<span class="tabular min-w-10 text-title-m font-semibold text-text group-hover/row:text-on-accent group-focus-visible/row:text-on-accent">{channel.number}</span>{/snippet}
		</LinkRow>
	{/each}
{/snippet}

<Page title="Channels" trail={[{ label: 'Home', href: '/' }]} note="Shared by every profile. Groups are the tabs in Live TV and number their channels.">
	{#snippet actions()}<Button label="New channel" icon={Add} primary href="/channels/new" />{/snippet}
	{#if error}<Notice text={error} error />{/if}
	<Search bind:value={query} label="Find a channel" />
	{#if needle}
		<Section title={section('Found', found.length)} />
		{#if found.length === 0}<Notice text="No channel has that name or number." />{/if}
		{@render rows(found)}
	{:else}
		<Section title="Live TV" />
		<LinkRow title="Groups" icon={Folder} href="/channels/groups" />
		<Section title="On Demand" />
		<ChoiceRow
			title="Banner"
			value={bannerSize}
			choices={bannerSizes}
			label={(size) => `${size} channels`}
			about="How many random channels show at the top of On Demand."
			onselect={setBanner}
			icon={ViewCarousel}
		/>
		{#if channels}
			<Section title={section('Custom channels', custom.length)} />
			{#if custom.length === 0}<Notice text="No custom channels yet." />{/if}
			{@render rows(custom)}
			<Section title={section('Library channels', library.length)} />
			{#if library.length === 0}<Notice text="No library channels yet." />{/if}
			{@render rows(library)}
		{/if}
	{/if}
</Page>
