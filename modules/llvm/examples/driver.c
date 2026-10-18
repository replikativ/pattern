#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

extern int64_t clj_count_primes(int64_t limit);
extern int64_t clj_sum_primes(int64_t limit);

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  errno = 0;
  char *end;
  int64_t limit = strtoll(argv[1], &end, 10);
  /* Keep the runnable demo bounded, including the Clojure reference workload. */
  if (errno || end == argv[1] || *end || limit < -1000000 || limit > 1000000) return 2;
  printf("%" PRId64 " %" PRId64 "\n", clj_count_primes(limit), clj_sum_primes(limit));
  return 0;
}
