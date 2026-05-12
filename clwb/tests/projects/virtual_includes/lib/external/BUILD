load("@rules_cc//cc:defs.bzl", "cc_library")

genrule(
    name = "generate",
    srcs = [],
    outs = ["generated.h"],
    cmd = r"""echo '#define EXTERNAL_GENERATED_MACRO 0' > $@""",
)

cc_library(
    name = "gen",
    srcs = [],
    hdrs = ["generated.h"],
    include_prefix = "external",
    visibility = ["//visibility:public"],
)

cc_library(
    name = "src_lib",
    srcs = ["external_source.cc"],
    visibility = ["//visibility:public"],
)
