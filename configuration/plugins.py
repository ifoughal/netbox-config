# Add your plugins and plugin settings here.
# Of course uncomment this file out.

# To learn how to build images with your required plugins
# See https://github.com/netbox-community/netbox-docker/wiki/Using-Netbox-Plugins

# Either netbox_bgp or netbox_routing, can't install both as they use the same accessor for prefix_list.
# issues:
#       https://github.com/netbox-community/netbox/discussions/14466
#       https://github.com/netbox-community/netbox-bgp/issues/221
PLUGINS = [
    # 'nb_risk',                  # incompatibile with 4.3 (<4.1.x)
    # 'netbox_acls',
    # 'netbox_bgp',               # using netbox_routing
    'netbox_contract',
    # 'netbox_qrcode',
    'netbox_routing',
    # 'netbox_secrets',
    'netbox_topology_views',
    'nextbox_ui_plugin',
    # 'netbox_diode_plugin',
    'netbox_custom_objects',
]


# PLUGINS_CONFIG = {
# #    'netbox_topology_views': {
# #        'static_image_directory': 'netbox_topology_views/img',
# #        'allow_coordinates_saving': True,
# #        'always_save_coordinates': True
# #    },
#     # "netbox_diode_plugin": {
#     #     # Diode gRPC target for communication with Diode server
#     #     "diode_target_override": "grpc://10.66.9.46:8080/diode",

#     #     # Username associated with changes applied via plugin
#     #     "diode_username": "diode",

#     #     # netbox-to-diode client_secret created during diode bootstrap.
#     #     "netbox_to_diode_client_secret": "p1lj5QW8glYcLTS6CmD+93tuwJ4WW2yZdpgI0F7fHg=",
#     # },
# }
