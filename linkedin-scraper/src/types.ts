/**
 * Types for the LinkedIn Scraper API.
 */

/** Job card shown in search results / list views */
export interface JobCard {
  id: string;
  title: string;
  company: string;
  location: string;
  postedAt: string;
  summary: string;
}

/** Full job details including description and requirements */
export interface JobDetail extends JobCard {
  description: string;
  requirements: string[];
  salary?: string;
  jobType?: string;
  seniority?: string;
  jobFunction?: string;
  industries?: string;
}

/** Standard API response wrapper */
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: ApiError;
}

/** Error detail returned in ApiResponse */
export interface ApiError {
  code: string;
  message: string;
}

/** Query parameters for job search */
export interface SearchParams {
  keywords: string;
  location?: string;
  page?: number;
}
