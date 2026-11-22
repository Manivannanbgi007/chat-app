import { Link } from 'react-router-dom'

export default function NotFoundPage() {
  return (
    <section className="text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="mt-2">The page you are looking for does not exist.</p>
      <Link to="/" className="mt-4 inline-block text-brand-600 underline">
        Go to the home page
      </Link>
    </section>
  )
}
