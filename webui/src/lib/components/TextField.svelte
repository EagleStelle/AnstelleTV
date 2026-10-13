<script lang="ts">
	import type { HTMLInputAttributes } from 'svelte/elements';

	/** A field on the surface with a hairline; it and its label take the accent while typed in. */
	interface Props extends HTMLInputAttributes {
		label: string;
		value: string;
		/** Set in the condensed cut with tabular figures, for numbers and codes. */
		tabular?: boolean;
	}

	let { label, value = $bindable(), tabular = false, class: extra, ...rest }: Props = $props();
</script>

<label class="group/field flex flex-col gap-1.5">
	<span class="text-body-m text-text-muted group-focus-within/field:text-accent">{label}</span>
	<input
		bind:value
		class={[
			'min-h-12 w-full rounded-control border border-line bg-surface px-3.5 text-text caret-accent focus:border-accent aria-invalid:border-accent',
			tabular && 'tabular',
			extra
		]}
		{...rest}
	/>
</label>
