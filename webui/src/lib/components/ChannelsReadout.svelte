<script lang="ts">
	import type { Overview } from '#lib/api.ts';
	import Folder from '~icons/material-symbols/folder';
	import Star from '~icons/material-symbols/star';
	import VisibilityOff from '~icons/material-symbols/visibility-off';
	import Fact from './Fact.svelte';
	import Facts from './Facts.svelte';
	import Figure from './Figure.svelte';
	import Figures from './Figures.svelte';
	import Readout from './Readout.svelte';
	import Rule from './Rule.svelte';
	import StatusLine from './StatusLine.svelte';

	/** How many channels air, who made them, and how they're kept. */
	let { stats }: { stats: Overview['channels'] } = $props();
</script>

<Readout>
	<StatusLine label="On air" text="{stats.visible} of {stats.total}" state={stats.visible > 0 ? 'on' : 'off'} />
	<Figures>
		<Figure value={stats.total} name={stats.total === 1 ? 'channel' : 'channels'} lead />
		<Figure value={stats.custom} name="custom" />
		<Figure value={stats.library} name="from your library" />
	</Figures>
	<Rule />
	<Facts>
		<Fact icon={Star} text={String(stats.favorites)} detail={stats.favorites === 1 ? 'favorite' : 'favorites'} />
		<Fact icon={Folder} text={String(stats.groups)} detail={stats.groups === 1 ? 'group' : 'groups'} />
		<Fact icon={VisibilityOff} text={String(stats.hidden)} detail="hidden" />
	</Facts>
</Readout>
