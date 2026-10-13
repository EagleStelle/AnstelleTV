<script lang="ts">
	/** A readout's facts: each name in the field voice over its value. */
	let { items }: { items: [string, string][] } = $props();
</script>

<dl class="m-0 flex flex-wrap gap-x-6 gap-y-2.5">
	{#each items as [name, value] (name)}
		<div>
			<dt class="field-label text-label-s text-text-muted">{name}</dt>
			<dd class="m-0 text-body-m wrap-anywhere">{value}</dd>
		</div>
	{/each}
</dl>
