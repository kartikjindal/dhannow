import { redirect } from "next/navigation";

/** The console has no public home: `/` goes to the staff dashboard (the middleware sends a visitor without a session to login). */
export default function Home() {
  redirect("/staff/dashboard");
}
