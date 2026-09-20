import type { VariantProps } from 'class-variance-authority';
import { Link, type LinkProps } from 'react-router-dom';
import { buttonVariants } from '@/components/ui/button-variants';
import { cn } from '@/lib/utils';

type ButtonLinkProps = LinkProps & VariantProps<typeof buttonVariants>;

/**
 * A link that looks like a button.
 *
 * A link rather than a button with an onClick handler, because these navigate: middle-click,
 * open-in-new-tab and the status bar preview all come free from an anchor and all have to be
 * rebuilt badly on top of a click handler.
 */
export function ButtonLink({ className, variant, size, ...props }: ButtonLinkProps) {
  return <Link className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
