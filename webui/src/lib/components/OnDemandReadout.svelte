<script lang="ts">
	import type { Overview } from '#lib/api.ts';
	import { kindIcon } from '#lib/ondemand.ts';
	import ViewAgenda from '~icons/material-symbols/view-agenda';
	import ViewCarousel from '~icons/material-symbols/view-carousel';
	import Fact from './Fact.svelte';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import ReadoutHeading from './ReadoutHeading.svelte';
	import Rule from './Rule.svelte';

	/** Each tab's banner size as figures, then the row each catalog tab opens with, behind its kind's icon. */
	let { onDemand }: { onDemand: Overview['onDemand'] } = $props();
</script>

<Readout>
	<ReadoutHeading icon={ViewCarousel} name="Banners" />
	<Figures>
		<Figure value={onDemand.homeBanner} name="Home" />
		{#each onDemand.kinds as kind (kind.key)}<Figure value={kind.banner} name={kind.label} />{/each}
	</Figures>
	<Rule />
	<ReadoutHeading icon={ViewAgenda} name="First rows" />
	<div class="flex flex-col gap-2">
		{#each onDemand.kinds as kind (kind.key)}
			<Fact icon={kindIcon(kind.key)} text={kind.firstRow ?? 'None yet'} quiet={!kind.firstRow} label={kind.label} />
		{/each}
	</div>
</Readout>
