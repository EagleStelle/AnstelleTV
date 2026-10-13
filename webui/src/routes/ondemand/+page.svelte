<script lang="ts">
	import { api, problem, type CatalogKey, type OnDemand } from '#lib/api.ts';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Section from '#lib/components/Section.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { kindIcon } from '#lib/ondemand.ts';
	import ViewCarousel from '~icons/material-symbols/view-carousel';
	import { onMount } from 'svelte';

	let onDemand = $state<OnDemand | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			onDemand = await api.onDemand();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['setting', 'channel', 'channel_rule', 'channel_daypart', 'media_item'], () => void load());
	});

	async function setBanner(kind: 'home' | CatalogKey, size: number) {
		try {
			await api.setBanner(kind, size);
			await load();
		} catch (e) {
			error = problem(e);
		}
	}

	const labelOf = (choices: { value: number; label: string }[]) => (size: number) => choices.find((c) => c.value === size)?.label ?? String(size);
</script>

<!-- How many random picks each tab's banner deals, then a way into each kind's rows, so Series is never under every row of Movies. -->
<Page
	title="On Demand"
	trail={[{ label: 'Home', href: '/' }]}
	note="Shared by every profile. Each tab's banner shows this many random picks. Movies and Series each keep their rows in an order of their own."
>
	{#if error}<Notice text={error} error />{/if}
	{#if onDemand}
		<Section title="Banners" />
		<ChoiceRow
			title="Home"
			value={onDemand.homeBanner}
			choices={onDemand.homeChoices.map((c) => c.value)}
			label={labelOf(onDemand.homeChoices)}
			about="How many random channels show at the top of Home."
			onselect={(size) => setBanner('home', size)}
			icon={ViewCarousel}
		/>
		{#each onDemand.kinds as kind (kind.key)}
			<ChoiceRow
				title={kind.label}
				value={kind.banner}
				choices={kind.bannerChoices.map((c) => c.value)}
				label={labelOf(kind.bannerChoices)}
				about="How many random {kind.label.toLowerCase()} show at the top of {kind.label}."
				onselect={(size) => setBanner(kind.key, size)}
				icon={kindIcon(kind.key)}
			/>
		{/each}
		<Section title="Rows" />
		{#each onDemand.kinds as kind (kind.key)}
			<LinkRow
				title={kind.label}
				summary={kind.rows.length === 0 ? 'None yet' : kind.rows.length === 1 ? '1 row' : `${kind.rows.length} rows`}
				icon={kindIcon(kind.key)}
				href="/ondemand/{kind.key}"
			/>
		{/each}
	{/if}
</Page>
