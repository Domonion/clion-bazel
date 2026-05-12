load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library")

genrule(
    name = "generate_source",
    srcs = [],
    outs = ["generated_source.cc"],
    cmd = r"""echo 'int generated_source() { return 42; }' > $@""",
)

cc_binary(
    name = "generated_source",
    srcs = ["generated_source.cc"],
    visibility = ["//visibility:public"],
)

cc_binary(
    name = "main",
    srcs = ["main.cc"],
    visibility = ["//visibility:public"],
    deps = [
        "//lib:gen",
        "//lib/impl_deps:lib",
        "//lib/raw_files:lib",
        "//lib/strip_absolut:gen",
        "//lib/strip_absolut:lib",
        "//lib/strip_relative:lib",
        "@clwb_virtual_includes_external//:gen",
        "@clwb_virtual_includes_external//:src_lib",
    ],
)

cc_binary(
    name = "raw",
    srcs = [
        "raw.cc",
        "//lib/raw_files:files",
    ],
    # basically manual strip absolut, different flags required for MSVC
    copts = select({
        "@platforms//os:windows": [
            "/Ilib/raw_files/default",
            "/external:Ilib/raw_files/system",
            "/external:Ilib/raw_files/quote",
        ],
        "//conditions:default": [
            "-Ilib/raw_files/default",
            "-isystemlib/raw_files/system",
            "-iquotelib/raw_files/quote",
        ],
    }),
)

cc_library(
    name = "header_only",
    hdrs = ["header_only.h"],
    visibility = ["//visibility:public"],
    deps = ["//lib/strip_absolut:lib"],
)
