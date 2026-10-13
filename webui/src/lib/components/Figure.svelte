<script lang="ts">
	import { number } from '#lib/format.ts';

	/** One figure: the number in tabular figures over its name; the lead one larger. */
	let { value, name, lead = false }: { value: number | string; name: string; lead?: boolean } = $props();
</script>

<div class="flex flex-col">
	<span class={['tabular font-semibold', lead ? 'text-display-s' : 'text-headline-s']}>{typeof value === 'number' ? number(value) : value}</span>
	<span class="field-label text-label-s text-text-muted">{name}</span>
</div>
