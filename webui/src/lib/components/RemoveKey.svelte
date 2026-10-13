<script lang="ts">
	import Delete from '~icons/material-symbols/delete-outline';

	/** A red bin at a row's end that removes the row's item at once. */
	let { label, onclick }: { label: string; onclick: () => void } = $props();
</script>

<button
	type="button"
	aria-label={label}
	title={label}
	{onclick}
	class="grid size-14 shrink-0 place-items-center rounded-control text-danger transition-colors hover:bg-danger hover:text-on-danger focus-visible:bg-danger focus-visible:text-on-danger"
>
	<Delete class="size-6" />
</button>
